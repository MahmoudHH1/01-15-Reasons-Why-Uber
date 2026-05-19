package com.team01.uber.payment.service;

import com.team01.uber.contracts.dto.RideDTO;
import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.contracts.events.PaymentCompletedEvent;
import com.team01.uber.contracts.events.PaymentFailedEvent;
import com.team01.uber.contracts.events.PaymentInitiatedEvent;
import com.team01.uber.contracts.events.PaymentRefundedEvent;
import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.feign.DriverServiceClient;
import com.team01.uber.contracts.feign.RideServiceClient;
import com.team01.uber.payment.client.DriverClient;
import com.team01.uber.payment.client.RideClient;
import com.team01.uber.payment.client.UserClient;
import feign.FeignException;
import com.team01.uber.payment.adapter.MongoDocumentAdapter;
import com.team01.uber.payment.dto.AppliedCouponDTO;
import com.team01.uber.payment.dto.PaymentDetailsDTO;
import com.team01.uber.payment.dto.PaymentMethodDTO;
import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.dto.RevenueReportDTO;
import com.team01.uber.payment.dto.ProcessPaymentRequest;
import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.dto.VehicleTypeRevenueDTO;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentMethod;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.observer.EntityObserver;
import com.team01.uber.payment.repository.PaymentRepository;
import com.team01.uber.payment.strategy.RefundContext;
import com.team01.uber.payment.strategy.RefundResult;
import com.team01.uber.payment.strategy.RefundStrategy;
import com.team01.uber.payment.strategy.RefundStrategySelector;
import com.team01.uber.payment.messaging.PaymentEventPublisher;
import org.bson.Document;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final RefundStrategySelector strategySelector;
    private final CacheInvalidationService cacheInvalidationService;
    private final MongoTemplate mongoTemplate;
    private final MongoDocumentAdapter mongoDocumentAdapter;
    private final PaymentEventPublisher paymentEventPublisher;
    private final UserClient userClient;
    private final RideClient rideClient;
    private final DriverClient driverClient;

    private final List<EntityObserver> observers = new ArrayList<>();

    public PaymentService(PaymentRepository paymentRepository,
                          RefundStrategySelector strategySelector,
                          CacheInvalidationService cacheInvalidationService,
                          MongoTemplate mongoTemplate,
                          MongoDocumentAdapter mongoDocumentAdapter,
                          PaymentEventPublisher paymentEventPublisher,
                          UserClient userClient,
                          RideClient rideClient,
                          DriverClient driverClient) {
        this.paymentRepository = paymentRepository;
        this.strategySelector = strategySelector;
        this.cacheInvalidationService = cacheInvalidationService;
        this.mongoTemplate = mongoTemplate;
        this.mongoDocumentAdapter = mongoDocumentAdapter;
        this.paymentEventPublisher = paymentEventPublisher;
        this.userClient = userClient;
        this.rideClient = rideClient;
        this.driverClient = driverClient;
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String eventType, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(eventType, payload);
        }
    }

    private void publishAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @Cacheable(value = "payment-service::S5-F9", key = "#userId")
    public UserPaymentSummaryDTO getUserPaymentSummary(Long userId) {
        MDC.put("userId", userId.toString());
        try {
            log.info("Calling UserClient.getUser with args={}", userId);
            userClient.getUser(userId);
            log.info("UserClient.getUser returned successfully");

            List<Object[]> rows = paymentRepository.findCompletedPaymentsSummaryByUser(userId);

            Map<String, Double> methodBreakdown = new HashMap<>();
            long totalPayments = 0;
            double totalAmount = 0.0;

            for (Object[] row : rows) {
                String method = (String) row[0];
                long count = ((Number) row[1]).longValue();
                double amount = ((Number) row[2]).doubleValue();

                methodBreakdown.put(method, amount);
                totalPayments += count;
                totalAmount += amount;
            }

            return UserPaymentSummaryDTO.builder()
                    .userId(userId)
                    .totalPayments(totalPayments)
                    .totalAmount(totalAmount)
                    .methodBreakdown(methodBreakdown)
                    .build();
        } finally {
            MDC.remove("userId");
        }
    }

    public Payment createPayment(Payment payment) {
        payment.setCreatedAt(LocalDateTime.now());
        if (payment.getStatus() == null) {
            payment.setStatus(PaymentStatus.PENDING);
        }
        Payment saved = paymentRepository.save(payment);
        log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());
        cacheInvalidationService.invalidatePattern("payment-service::S5-F1::*");
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", saved.getId());
        if (saved.getMethod() != null) payload.put("method", saved.getMethod().name());
        payload.put("amount", saved.getAmount());
        notifyObservers("PAYMENT_CREATED", payload);
        return saved;
    }

    @Transactional
    public Payment processRefund(Long id, String reason) {
        MDC.put("paymentId", id.toString());
        try {
            Payment payment = paymentRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

            if (payment.getStatus() != PaymentStatus.COMPLETED) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only COMPLETED payments can be refunded");
            }

            payment.setStatus(PaymentStatus.REFUNDED);

            if (payment.getTransactionDetails() == null) {
                payment.setTransactionDetails(new HashMap<>());
            }
            payment.getTransactionDetails().put("refundReason", reason);
            payment.getTransactionDetails().put("refundedAt", LocalDateTime.now().toString());

            Payment saved = paymentRepository.save(payment);
            log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());

            notifyObservers("REFUNDED", Map.of(
                    "paymentId", saved.getId(),
                    "method", saved.getMethod().name(),
                    "amount", saved.getAmount(),
                    "details", Map.of("reason", reason)
            ));

            cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());

            final long refundedPaymentId = saved.getId();
            final long refundedRideId = saved.getRideId();
            final double refundedAmount = saved.getAmount();
            publishAfterCommit(() -> paymentEventPublisher.publishRefunded(
                    new PaymentRefundedEvent(refundedPaymentId, refundedRideId, refundedAmount)));

            return saved;
        } finally {
            MDC.remove("paymentId");
        }
    }

    @Transactional
    public Payment processRefundSurgeAdjusted(Long id, RefundSurgeRequest request) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only COMPLETED payments can be refunded");
        }

        RefundContext ctx = new RefundContext(paymentRepository, this::notifyObservers, cacheInvalidationService);
        RefundStrategy strategy = strategySelector.select(payment, request);
        RefundResult result = strategy.calculateRefund(payment, request);
        Payment saved = result.apply(payment, request, ctx, strategy.getClass().getSimpleName());

        if (saved.getStatus() == PaymentStatus.REFUNDED) {
            final long refundedPaymentId = saved.getId();
            final long refundedRideId = saved.getRideId();
            final double refundedAmount = result.getAmount() > 0 ? result.getAmount() : saved.getAmount();
            publishAfterCommit(() -> paymentEventPublisher.publishRefunded(
                    new PaymentRefundedEvent(refundedPaymentId, refundedRideId, refundedAmount)));
        }

        return saved;
    }

    @Cacheable(value = "payment-service::payment", key = "#id")
    public Payment getPaymentById(Long id) {
        MDC.put("paymentId", id.toString());
        try {
            return paymentRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        } finally {
            MDC.remove("paymentId");
        }
    }

    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }

    public Payment updatePayment(Long id, Payment payment) {
        MDC.put("paymentId", id.toString());
        try {
            Payment existing = getPaymentById(id);
            existing.setRideId(payment.getRideId());
            existing.setUserId(payment.getUserId());
            existing.setAmount(payment.getAmount());
            existing.setMethod(payment.getMethod());
            existing.setStatus(payment.getStatus());
            existing.setTransactionDetails(payment.getTransactionDetails());
            Payment saved = paymentRepository.save(existing);
            log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());
            cacheInvalidationService.invalidateAllPaymentFeatureCaches(id);
            Map<String, Object> updatePayload = new HashMap<>();
            updatePayload.put("paymentId", saved.getId());
            if (saved.getMethod() != null) updatePayload.put("method", saved.getMethod().name());
            updatePayload.put("amount", saved.getAmount());
            notifyObservers("PAYMENT_UPDATED", updatePayload);
            return saved;
        } finally {
            MDC.remove("paymentId");
        }
    }

    public void deletePayment(Long id) {
        if (!paymentRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        paymentRepository.deleteById(id);
        cacheInvalidationService.invalidateAllPaymentFeatureCaches(id);
        Map<String, Object> deletePayload = new HashMap<>();
        deletePayload.put("paymentId", id);
        notifyObservers("PAYMENT_DELETED", deletePayload);
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Payment processPaymentForRide(Long rideId, ProcessPaymentRequest request, boolean simulateFailure) {
        MDC.put("rideId", rideId.toString());
        RideDTO ride;
        log.info("Calling rideClient.getRide with args={}", rideId);
        ride = rideClient.getRide(rideId);
        log.info("rideClient.getRide returned successfully");

        if (!"PAYMENT_PENDING".equals(ride.status()) && !"COMPLETED".equals(ride.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride is not in a payable status");
        }

        if (paymentRepository.existsByRideIdAndStatus(rideId, PaymentStatus.COMPLETED)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "already paid");
        }

        final RideDTO finalRide = ride;
        Payment payment = paymentRepository.findByRideIdAndStatus(rideId, PaymentStatus.PENDING)
                .or(() -> paymentRepository.findByRideIdAndStatus(rideId, PaymentStatus.FAILED))
                .orElseGet(() -> {
                    Payment newPayment = new Payment();
                    newPayment.setRideId(rideId);
                    newPayment.setUserId(finalRide.userId());
                    newPayment.setAmount(finalRide.fare() != null ? finalRide.fare() : 0.0);
                    newPayment.setCreatedAt(LocalDateTime.now());
                    return newPayment;
                });

        PaymentMethod parsedMethod;
        try {
            parsedMethod = PaymentMethod.valueOf(request.getMethod());
        } catch (IllegalArgumentException | NullPointerException ex) {
            payment.setStatus(PaymentStatus.FAILED);
            Map<String, Object> failDetails = payment.getTransactionDetails() != null
                    ? payment.getTransactionDetails() : new HashMap<>();
            failDetails.put("gatewayResponse", "declined");
            failDetails.put("failureReason", "unsupported payment method: " + request.getMethod());
            payment.setTransactionDetails(failDetails);
            Payment savedFail = paymentRepository.save(payment);
            MDC.put("paymentId", savedFail.getId().toString());
            log.info("{} {} saved with status={}", "Payment", savedFail.getId(), savedFail.getStatus());
            final long failPaymentId = savedFail.getId();
            final String reason = "unsupported payment method: " + request.getMethod();
            publishAfterCommit(() -> paymentEventPublisher.publishFailed(
                    new PaymentFailedEvent(failPaymentId, rideId, reason)));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
        payment.setMethod(parsedMethod);

        Map<String, Object> details = payment.getTransactionDetails() != null
                ? payment.getTransactionDetails()
                : new HashMap<>();

        try {
            if (simulateFailure) {
                payment.setStatus(PaymentStatus.FAILED);
                details.put("gatewayResponse", "declined");
                details.put("failureReason", "simulated gateway failure");
                payment.setTransactionDetails(details);

                Payment saved = paymentRepository.save(payment);
                log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());
                MDC.put("paymentId", saved.getId().toString());
                notifyObservers("FAILED", Map.of(
                        "paymentId", saved.getId(),
                        "method", saved.getMethod().name(),
                        "amount", saved.getAmount(),
                        "details", Map.of(
                                "failureReason", "simulated gateway failure",
                                "rideId", rideId
                        )
                ));
                final long failedPaymentId = saved.getId();
                publishAfterCommit(() -> paymentEventPublisher.publishFailed(
                        new PaymentFailedEvent(failedPaymentId, rideId, "simulated gateway failure")));
                return saved;
            }

            payment.setStatus(PaymentStatus.COMPLETED);
            details.put("gatewayResponse", "approved");
            if (request.getCardLastFour() != null) {
                details.put("cardLastFour", request.getCardLastFour());
            }

            double surgeFee = computeSurgeFee(rideId, payment.getAmount());
            details.put("surgeFee", surgeFee);

            payment.setTransactionDetails(details);

            Payment saved = paymentRepository.save(payment);
            log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());
            MDC.put("paymentId", saved.getId().toString());
            cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());

            notifyObservers("CREATED", Map.of(
                    "paymentId", saved.getId(),
                    "method", saved.getMethod().name(),
                    "amount", saved.getAmount(),
                    "details", Map.of("rideId", rideId)
            ));

            notifyObservers("COMPLETED", Map.of(
                    "paymentId", saved.getId(),
                    "method", saved.getMethod().name(),
                    "amount", saved.getAmount(),
                    "details", Map.of(
                            "gatewayResponse", "approved",
                            "rideId", rideId,
                            "surgeFee", surgeFee
                    )
            ));

            final long completedPaymentId = saved.getId();
            final double completedAmount = saved.getAmount();
            publishAfterCommit(() -> paymentEventPublisher.publishCompleted(
                    new PaymentCompletedEvent(completedPaymentId, rideId, completedAmount)));

            return saved;
        } finally {
            MDC.remove("paymentId");
            MDC.remove("rideId");
        }
    }

    private double computeSurgeFee(Long rideId, double amount) {
        return amount * 0.15;
    }

    @Transactional
    public Payment retryFailedPayment(Long id) {
        Payment payment = paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        if (payment.getStatus() != PaymentStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only FAILED payments can be retried");
        }

        payment.setStatus(PaymentStatus.COMPLETED);

        if (payment.getTransactionDetails() == null) {
            payment.setTransactionDetails(new HashMap<>());
        }
        Map<String, Object> details = payment.getTransactionDetails();
        int currentRetry = details.containsKey("retryAttempt")
                ? ((Number) details.get("retryAttempt")).intValue()
                : 0;
        details.put("retryAttempt", currentRetry + 1);
        details.put("gatewayResponse", "approved");

        Payment saved = paymentRepository.save(payment);
        log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());
        cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());
        Map<String, Object> retryPayload = new HashMap<>();
        retryPayload.put("paymentId", saved.getId());
        if (saved.getMethod() != null) retryPayload.put("method", saved.getMethod().name());
        retryPayload.put("amount", saved.getAmount());
        retryPayload.put("retryAttempt", currentRetry + 1);
        notifyObservers("RETRY_ATTEMPTED", retryPayload);
        return saved;
    }

    @Cacheable(value = "payment-service::S5-F8", key = "#paymentId")
    @Transactional(readOnly = true)
    public PaymentDetailsDTO getPaymentDetails(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));

        List<AppliedCouponDTO> appliedCoupons = payment.getPaymentCoupons().stream()
                .map(pc -> new AppliedCouponDTO(
                        pc.getCoupon().getCode(),
                        pc.getCoupon().getDiscountType(),
                        pc.getDiscountApplied(),
                        pc.getAppliedAt()
                ))
                .toList();

        double totalDiscount = appliedCoupons.stream()
                .mapToDouble(AppliedCouponDTO::getDiscountApplied)
                .sum();

        return PaymentDetailsDTO.builder()
                .paymentId(payment.getId())
                .rideId(payment.getRideId())
                .userId(payment.getUserId())
                .originalAmount(payment.getAmount())
                .method(payment.getMethod())
                .status(payment.getStatus())
                .transactionDetails(payment.getTransactionDetails())
                .appliedCoupons(appliedCoupons)
                .totalDiscount(totalDiscount)
                .finalAmount(payment.getAmount() - totalDiscount)
                .build();
    }

    @Cacheable(value = "payment-service::S5-F1", key = "#status + ':' + #startDate + ':' + #endDate + ':' + #userId")
    public List<Payment> searchPayments(PaymentStatus status, LocalDateTime startDate, LocalDateTime endDate, Long userId) {
        String statusStr = status != null ? status.name() : null;
        return paymentRepository.findByStatusAndDateRange(statusStr, startDate, endDate, userId);
    }

    @Cacheable(value = "payment-service::S5-F6", key = "#startDate + ':' + #endDate")
    public RevenueReportDTO getRevenueReport(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate must be before endDate");
        }

        Object[] completedRow = paymentRepository.getCompletedRevenueInRange(startDate, endDate).get(0);
        double totalRevenue = ((Number) completedRow[0]).doubleValue();
        long totalTransactions = ((Number) completedRow[1]).longValue();

        double averagePayment = totalTransactions > 0 ? totalRevenue / totalTransactions : 0;

        Object[] refundedRow = paymentRepository.getRefundedAmountInRange(startDate, endDate).get(0);
        double refundedAmount = ((Number) refundedRow[0]).doubleValue();
        long refundCount = ((Number) refundedRow[1]).longValue();

        return RevenueReportDTO.builder()
                .totalRevenue(totalRevenue)
                .totalTransactions(totalTransactions)
                .averagePayment(averagePayment)
                .refundedAmount(refundedAmount)
                .refundCount(refundCount)
                .build();
    }

    @Cacheable(value = "payment-service::S5-F10", key = "#startDate + ':' + #endDate")
    public List<VehicleTypeRevenueDTO> getVehicleTypeRevenue(LocalDateTime startDate, LocalDateTime endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate must be before endDate");
        }

        // Cap candidate set to 100 per M3 N+1 fan-out rule
        List<Payment> payments = paymentRepository.findCompletedPaymentsInDateRange(startDate, endDate);

        // rideId → driverId via Feign (deduplicated)
        Map<Long, Long> rideToDriver = new HashMap<>();
        for (Long rideId : payments.stream().map(Payment::getRideId).filter(r -> r != null).collect(Collectors.toSet())) {
            log.info("Calling rideClient.getRide with args={}", rideId);
            RideDTO ride = rideClient.getRide(rideId);
            if (ride.driverId() != null) rideToDriver.put(rideId, ride.driverId());
            log.info("rideClient.getRide returned successfully");
        }

        // driverId → vehicleType via Feign (deduplicated)
        Map<Long, String> driverToVehicleType = new HashMap<>();
        for (Long driverId : new HashSet<>(rideToDriver.values())) {
            log.info("Calling driverClient.getDriver with args={}", driverId);
            com.team01.uber.contracts.dto.DriverDTO driver = driverClient.getDriver(driverId);
            String vt = driver.vehicleDetails() != null ? (String) driver.vehicleDetails().get("vehicleType") : null;
            driverToVehicleType.put(driverId, vt != null ? vt : "UNKNOWN");
            log.info("driverClient.getDriver returned successfully");
        }

        // Aggregate by vehicleType in Java
        Map<String, double[]> agg = new HashMap<>();
        for (Payment p : payments) {
            if (p.getRideId() == null) continue;
            Long driverId = rideToDriver.get(p.getRideId());
            if (driverId == null) continue;
            String vehicleType = driverToVehicleType.getOrDefault(driverId, "UNKNOWN");

            double surgeFee = 0.0;
            if (p.getTransactionDetails() != null && p.getTransactionDetails().get("surgeFee") != null) {
                surgeFee = ((Number) p.getTransactionDetails().get("surgeFee")).doubleValue();
            } else {
                surgeFee = p.getAmount() * 0.15;
            }

            double[] bucket = agg.computeIfAbsent(vehicleType, k -> new double[3]);
            bucket[0] += p.getAmount();
            bucket[1] += surgeFee;
            bucket[2] += 1;
        }

        return agg.entrySet().stream()
                .map(e -> VehicleTypeRevenueDTO.builder()
                        .vehicleType(e.getKey())
                        .totalRevenue(e.getValue()[0])
                        .surgeFeeRevenue(e.getValue()[1])
                        .baseFareRevenue(e.getValue()[0] - e.getValue()[1])
                        .rideCount((long) e.getValue()[2])
                        .build())
                .toList();
    }

    public BigDecimal getUserPaymentTotal(Long userId, LocalDateTime startDate, LocalDateTime endDate) {
        MDC.put("userId", userId.toString());
        try {
            return paymentRepository.getUserPaymentTotal(userId, startDate, endDate);
        } finally {
            MDC.remove("userId");
        }
    }

    public void logAnalyticsViewed(LocalDateTime startDate, LocalDateTime endDate) {
        notifyObservers("ANALYTICS_VIEWED", Map.of(
                "details", Map.of("startDate", startDate.toString(), "endDate", endDate.toString())
        ));
    }

    @Cacheable(value = "payment-service::S5-F11", key = "#start.toString() + '-' + #end.toString()")
    public List<PaymentMethodDTO> getPaymentMethodBreakdown(LocalDateTime start, LocalDateTime end) {
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must be before endDate");
        }

        MatchOperation match = Aggregation.match(
                Criteria.where("timestamp").gte(start).lte(end)
                        .and("action").in("COMPLETED", "FAILED")
        );

        GroupOperation group = Aggregation.group("method")
                .sum(ConditionalOperators.when(Criteria.where("action").is("COMPLETED")).then(1).otherwise(0))
                .as("successCount")
                .sum(ConditionalOperators.when(Criteria.where("action").is("FAILED")).then(1).otherwise(0))
                .as("failureCount")
                .sum(ConditionalOperators.when(Criteria.where("action").is("COMPLETED"))
                        .thenValueOf("amount").otherwise(0))
                .as("totalAmount");

        Aggregation aggregation = Aggregation.newAggregation(match, group);
        AggregationResults<Document> results = mongoTemplate.aggregate(
                aggregation, "payment_audit_trail", Document.class);

        return results.getMappedResults().stream()
                .map(mongoDocumentAdapter::adapt)
                .toList();
    }

    @Transactional
    public void processRideCompleted(RideCompletedEvent event) {
        MDC.put("rideId", event.rideId().toString());
        MDC.put("routingKey", "ride.completed");
        try {
            log.info("Consuming ride.completed for rideId={}", event.rideId());

            boolean alreadyExists = paymentRepository.findByRideIdAndStatus(event.rideId(), PaymentStatus.PENDING).isPresent()
                    || paymentRepository.findByRideIdAndStatus(event.rideId(), PaymentStatus.COMPLETED).isPresent();
            if (alreadyExists) {
                log.info("Payment already exists for rideId={}, skipping ride.completed", event.rideId());
                return;
            }

            Payment payment = new Payment();
            payment.setRideId(event.rideId());
            payment.setUserId(event.userId());
            payment.setAmount(event.fare() != null ? event.fare() : 0.0);
            payment.setMethod(PaymentMethod.CASH);
            payment.setStatus(PaymentStatus.PENDING);
            payment.setCreatedAt(LocalDateTime.now());

            Payment saved = paymentRepository.save(payment);
            MDC.put("paymentId", saved.getId().toString());
            log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());

            Map<String, Object> payload = new HashMap<>();
            payload.put("paymentId", saved.getId());
            payload.put("amount", saved.getAmount());
            notifyObservers("CREATED", payload);

            final long initiatedPaymentId = saved.getId();
            final double initiatedAmount = saved.getAmount();
            final long initiatedRideId = event.rideId();
            publishAfterCommit(() -> paymentEventPublisher.publishInitiated(
                    new PaymentInitiatedEvent(initiatedPaymentId, initiatedRideId, initiatedAmount)));

            log.info("Processed ride.completed for rideId={}, created paymentId={}", event.rideId(), saved.getId());
        } finally {
            MDC.remove("rideId");
            MDC.remove("paymentId");
            MDC.remove("routingKey");
        }
    }

    @Transactional
    public void processRideCancelled(RideCancelledEvent event) {
        MDC.put("rideId", event.rideId().toString());
        MDC.put("routingKey", "ride.cancelled");
        try {
            log.info("Consuming ride.cancelled for rideId={}", event.rideId());

            if (paymentRepository.findByRideIdAndStatus(event.rideId(), PaymentStatus.REFUNDED).isPresent()) {
                log.info("Payment already refunded for rideId={}, skipping ride.cancelled", event.rideId());
                return;
            }

            Payment payment = paymentRepository.findByRideIdAndStatus(event.rideId(), PaymentStatus.PENDING)
                    .or(() -> paymentRepository.findByRideIdAndStatus(event.rideId(), PaymentStatus.FAILED))
                    .orElse(null);

            if (payment == null) {
                log.info("No refundable payment for rideId={}, skipping ride.cancelled", event.rideId());
                return;
            }

            MDC.put("paymentId", payment.getId().toString());

            RefundSurgeRequest req = new RefundSurgeRequest();
            req.setReason("ride_cancelled");
            req.setRefundSurge(true);

            RefundStrategy strategy = strategySelector.select(payment, req);
            RefundResult result = strategy.calculateRefund(payment, req);
            double refundAmount = result.getAmount() > 0 ? result.getAmount() : payment.getAmount();

            payment.setStatus(PaymentStatus.REFUNDED);
            if (payment.getTransactionDetails() == null) {
                payment.setTransactionDetails(new HashMap<>());
            }
            payment.getTransactionDetails().put("refundAmount", refundAmount);
            payment.getTransactionDetails().put("refundReason", req.getReason());
            payment.getTransactionDetails().put("refundSurgeIncluded", req.isRefundSurge());
            payment.getTransactionDetails().put("refundedAt", LocalDateTime.now().toString());
            payment.getTransactionDetails().put("strategyName", strategy.getClass().getSimpleName());

            Payment saved = paymentRepository.save(payment);
            log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());

            Map<String, Object> notifyPayload = new HashMap<>();
            notifyPayload.put("paymentId", saved.getId());
            if (saved.getMethod() != null) notifyPayload.put("method", saved.getMethod().name());
            notifyPayload.put("amount", saved.getAmount());
            notifyObservers("REFUNDED", notifyPayload);
            cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());

            final long refundedPaymentId = saved.getId();
            final long refundedRideId = event.rideId();
            final double finalRefundAmount = refundAmount;
            publishAfterCommit(() -> paymentEventPublisher.publishRefunded(
                    new PaymentRefundedEvent(refundedPaymentId, refundedRideId, finalRefundAmount)));

            log.info("Processed ride.cancelled for rideId={}, refunded paymentId={}", event.rideId(), saved.getId());
        } finally {
            MDC.remove("rideId");
            MDC.remove("paymentId");
            MDC.remove("routingKey");
        }
    }
}
