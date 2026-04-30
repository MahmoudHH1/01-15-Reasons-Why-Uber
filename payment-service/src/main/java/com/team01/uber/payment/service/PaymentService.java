package com.team01.uber.payment.service;

import com.team01.uber.payment.dto.AppliedCouponDTO;
import com.team01.uber.payment.dto.PaymentDetailsDTO;
import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.dto.RevenueReportDTO;
import com.team01.uber.payment.dto.ProcessPaymentRequest;
import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.dto.VehicleTypeRevenueDTO;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.observer.EntityObserver;
import com.team01.uber.payment.repository.PaymentRepository;
import com.team01.uber.payment.strategy.RefundContext;
import com.team01.uber.payment.strategy.RefundStrategySelector;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RefundStrategySelector strategySelector;
    private final CacheInvalidationService cacheInvalidationService;

    private final List<EntityObserver> observers = new ArrayList<>();

    public PaymentService(PaymentRepository paymentRepository,
                          RefundStrategySelector strategySelector,
                          CacheInvalidationService cacheInvalidationService) {
        this.paymentRepository = paymentRepository;
        this.strategySelector = strategySelector;
        this.cacheInvalidationService = cacheInvalidationService;
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

    @Cacheable(value = "payment-service::S5-F9", key = "#userId")
    public UserPaymentSummaryDTO getUserPaymentSummary(Long userId) {
        if (paymentRepository.countUsersById(userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

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
    }

    public Payment createPayment(Payment payment) {
        payment.setCreatedAt(LocalDateTime.now());
        if (payment.getStatus() == null) {
            payment.setStatus(PaymentStatus.PENDING);
        }
        return paymentRepository.save(payment);
    }

    @Transactional
    public Payment processRefund(Long id, String reason) {
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

        notifyObservers("REFUNDED", Map.of(
                "paymentId", saved.getId(),
                "method", saved.getMethod().name(),
                "amount", saved.getAmount(),
                "details", Map.of("reason", reason)
        ));

        cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());
        return saved;
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
        return strategySelector.select(payment, request).execute(payment, request, ctx);
    }

    @Cacheable(value = "payment-service::payment", key = "#id")
    public Payment getPaymentById(Long id) {
        return paymentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
    }

    public List<Payment> getAllPayments() {
        return paymentRepository.findAll();
    }

    public Payment updatePayment(Long id, Payment payment) {
        Payment existing = getPaymentById(id);
        existing.setRideId(payment.getRideId());
        existing.setUserId(payment.getUserId());
        existing.setAmount(payment.getAmount());
        existing.setMethod(payment.getMethod());
        existing.setStatus(payment.getStatus());
        existing.setTransactionDetails(payment.getTransactionDetails());
        Payment saved = paymentRepository.save(existing);
        cacheInvalidationService.invalidateAllPaymentFeatureCaches(id);
        return saved;
    }

    public void deletePayment(Long id) {
        if (!paymentRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found");
        }
        paymentRepository.deleteById(id);
        cacheInvalidationService.invalidateAllPaymentFeatureCaches(id);
    }

    @Transactional
    public Payment processPaymentForRide(Long rideId, ProcessPaymentRequest request) {
        String rideStatus = paymentRepository.findRideStatusById(rideId);
        if (rideStatus == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        if (!"COMPLETED".equals(rideStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride is not COMPLETED");
        }

        if (paymentRepository.existsByRideIdAndStatus(rideId, PaymentStatus.COMPLETED)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "already paid");
        }

        Payment payment = paymentRepository.findByRideIdAndStatus(rideId, PaymentStatus.PENDING)
                .orElseGet(() -> {
                    Payment newPayment = new Payment();
                    newPayment.setRideId(rideId);
                    newPayment.setUserId(paymentRepository.findRideUserIdById(rideId));
                    Double fare = paymentRepository.findRideFareById(rideId);
                    newPayment.setAmount(fare != null ? fare : 0.0);
                    newPayment.setCreatedAt(LocalDateTime.now());
                    return newPayment;
                });

        payment.setMethod(request.getMethod());
        payment.setStatus(PaymentStatus.COMPLETED);

        Map<String, Object> details = payment.getTransactionDetails() != null
                ? payment.getTransactionDetails()
                : new HashMap<>();
        details.put("gatewayResponse", "approved");
        if (request.getCardLastFour() != null) {
            details.put("cardLastFour", request.getCardLastFour());
        }

        double surgeFee = computeSurgeFee(rideId, payment.getAmount());
        details.put("surgeFee", surgeFee);

        payment.setTransactionDetails(details);

        Payment saved = paymentRepository.save(payment);
        cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());
        return saved;
    }

    private double computeSurgeFee(Long rideId, double amount) {
        try {
            Double surgeMultiplier = paymentRepository.findRideSurgeMultiplierById(rideId);
            if (surgeMultiplier != null && surgeMultiplier > 1.0) {
                Double fare = paymentRepository.findRideFareById(rideId);
                double baseFare = fare != null ? fare : amount;
                return baseFare * (surgeMultiplier - 1.0);
            }
        } catch (Exception ignored) {
        }
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
        cacheInvalidationService.invalidateAllPaymentFeatureCaches(saved.getId());
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

    @Cacheable(value = "payment-service::S5-F1", key = "#status + ':' + #startDate + ':' + #endDate")
    public List<Payment> searchPayments(PaymentStatus status, LocalDateTime startDate, LocalDateTime endDate) {
        String statusStr = status != null ? status.name() : null;
        return paymentRepository.findByStatusAndDateRange(statusStr, startDate, endDate);
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

        List<Object[]> rows = paymentRepository.findRevenueByVehicleType(startDate, endDate);

        return rows.stream().map(row -> {
            String vehicleType    = (String) row[0];
            double totalRevenue   = ((Number) row[1]).doubleValue();
            double surgeFeeRevenue = ((Number) row[2]).doubleValue();
            double baseFareRevenue = totalRevenue - surgeFeeRevenue;
            long rideCount        = ((Number) row[3]).longValue();

            return VehicleTypeRevenueDTO.builder()
                    .vehicleType(vehicleType)
                    .baseFareRevenue(baseFareRevenue)
                    .surgeFeeRevenue(surgeFeeRevenue)
                    .totalRevenue(totalRevenue)
                    .rideCount(rideCount)
                    .build();
        }).toList();
    }

    public void logAnalyticsViewed(LocalDateTime startDate, LocalDateTime endDate) {
        notifyObservers("ANALYTICS_VIEWED", Map.of(
                "details", Map.of("startDate", startDate.toString(), "endDate", endDate.toString())
        ));
    }
}
