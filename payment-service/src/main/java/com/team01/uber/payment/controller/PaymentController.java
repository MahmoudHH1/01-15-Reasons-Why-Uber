package com.team01.uber.payment.controller;

import com.team01.uber.payment.dto.CouponUsageDTO;
import com.team01.uber.payment.dto.PaymentDetailsDTO;
import com.team01.uber.payment.dto.PaymentMethodDTO;
import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.dto.RevenueReportDTO;
import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.dto.VehicleTypeRevenueDTO;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.service.CouponService;
import com.team01.uber.payment.service.PaymentCouponService;
import com.team01.uber.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.team01.uber.payment.dto.PaymentWithCouponsDTO;
import com.team01.uber.payment.dto.ProcessPaymentRequest;
import com.team01.uber.payment.dto.RefundRequest;
import com.team01.uber.payment.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final CouponService couponService;
    private final PaymentCouponService paymentCouponService;

    public PaymentController(PaymentService paymentService, CouponService couponService,
                             PaymentCouponService paymentCouponService) {
        this.paymentService = paymentService;
        this.couponService = couponService;
        this.paymentCouponService = paymentCouponService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @GetMapping("/coupons/top-used")
    public ResponseEntity<List<CouponUsageDTO>> getTopUsedCoupons(@RequestParam int limit) {
        return ResponseEntity.ok(couponService.getMostUsedCoupons(limit));
    }

    @GetMapping("/user/{userId}/summary")
    public UserPaymentSummaryDTO getUserPaymentSummary(@PathVariable Long userId, HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        UserPaymentSummaryDTO result = paymentService.getUserPaymentSummary(userId);
        log.info("Returning {} for {} {}", 200, request.getMethod(), request.getRequestURI());
        return result;
    }

    @GetMapping("/user/{userId}/total")
    public ResponseEntity<BigDecimal> getUserPaymentTotal(
            @PathVariable Long userId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        ResponseEntity<BigDecimal> response = ResponseEntity.ok(paymentService.getUserPaymentTotal(userId, parseStartDate(startDate), parseEndDate(endDate)));
        log.info("Returning {} for {} {}", response.getStatusCode().value(), request.getMethod(), request.getRequestURI());
        return response;
    }

    @PutMapping("/{id}/refund")
    public Payment refund(@PathVariable Long id, @Valid @RequestBody RefundRequest request) {
        return paymentService.processRefund(id, request.getReason());
    }

    @PostMapping("/{id}/refund-surge-adjusted")
    public ResponseEntity<Payment> refundSurgeAdjusted(@PathVariable Long id,
                                                        @Valid @RequestBody RefundSurgeRequest request) {
        return ResponseEntity.ok(paymentService.processRefundSurgeAdjusted(id, request));
    }

    @PostMapping
    public ResponseEntity<Payment> createPayment(@RequestBody Payment payment, HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        ResponseEntity<Payment> response = ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPayment(payment));
        log.info("Returning {} for {} {}", response.getStatusCode().value(), request.getMethod(), request.getRequestURI());
        return response;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPaymentById(@PathVariable Long id, HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        ResponseEntity<Payment> response = ResponseEntity.ok(paymentService.getPaymentById(id));
        log.info("Returning {} for {} {}", response.getStatusCode().value(), request.getMethod(), request.getRequestURI());
        return response;
    }

    @GetMapping
    public ResponseEntity<List<Payment>> getAllPayments(HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        ResponseEntity<List<Payment>> response = ResponseEntity.ok(paymentService.getAllPayments());
        log.info("Returning {} for {} {}", response.getStatusCode().value(), request.getMethod(), request.getRequestURI());
        return response;
    }

    @PutMapping("/{id}")
    public ResponseEntity<Payment> updatePayment(@PathVariable Long id, @RequestBody Payment payment, HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        ResponseEntity<Payment> response = ResponseEntity.ok(paymentService.updatePayment(id, payment));
        log.info("Returning {} for {} {}", response.getStatusCode().value(), request.getMethod(), request.getRequestURI());
        return response;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id, HttpServletRequest request) {
        log.info("Received {} {}", request.getMethod(), request.getRequestURI());
        paymentService.deletePayment(id);
        log.info("Returning {} for {} {}", 204, request.getMethod(), request.getRequestURI());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/analytics/vehicle-type")
    public ResponseEntity<List<VehicleTypeRevenueDTO>> getVehicleTypeRevenue(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        LocalDateTime start = parseStartDate(startDate);
        LocalDateTime end   = parseEndDate(endDate);

        List<VehicleTypeRevenueDTO> result = paymentService.getVehicleTypeRevenue(start, end);
        paymentService.logAnalyticsViewed(start, end);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/reports/revenue")
    public RevenueReportDTO getRevenueReport(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return paymentService.getRevenueReport(parseStartDate(startDate), parseEndDate(endDate));
    }

    @GetMapping("/search")
    public List<Payment> searchPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) Long userId
    ) {
        LocalDateTime start = startDate == null ? null : parseStartDate(startDate);
        LocalDateTime end = endDate == null ? null : parseEndDate(endDate);
        return paymentService.searchPayments(status, start, end, userId);
    }

    private LocalDateTime   parseStartDate(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr);
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDate.parse(dateStr).atStartOfDay();
        }
    }

    private LocalDateTime parseEndDate(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr);
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDate.parse(dateStr).atTime(23, 59, 59, 999_000_000);
        }
    }
    @PutMapping("/{id}/retry")
    public ResponseEntity<Payment> retryPayment(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.retryFailedPayment(id));
    }

    @GetMapping("/{paymentId}/details")
    public ResponseEntity<PaymentDetailsDTO> getPaymentDetails(@PathVariable Long paymentId) {
        return ResponseEntity.ok(paymentService.getPaymentDetails(paymentId));
    }

    @PostMapping("/{paymentId}/coupons/{couponId}")
    public ResponseEntity<PaymentWithCouponsDTO> applyCouponToPayment(@PathVariable Long paymentId,
                                                                      @PathVariable Long couponId) {
        return ResponseEntity.ok(paymentCouponService.applyCouponToPayment(paymentId, couponId));
    }

    @PostMapping("/ride/{rideId}")
    public ResponseEntity<Payment> processPaymentForRide(
            @PathVariable Long rideId,
            @Valid @RequestBody ProcessPaymentRequest request,
            @RequestParam(name = "simulateFailure", required = false, defaultValue = "false") boolean simulateFailure,
            HttpServletRequest httpRequest) {
        log.info("Received {} {}", httpRequest.getMethod(), httpRequest.getRequestURI());
        ResponseEntity<Payment> response = ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.processPaymentForRide(rideId, request, simulateFailure));
        log.info("Returning {} for {} {}", response.getStatusCode().value(), httpRequest.getMethod(), httpRequest.getRequestURI());
        return response;
    }

    @GetMapping("/analytics/methods")
    public ResponseEntity<List<PaymentMethodDTO>> getPaymentMethodBreakdown(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(paymentService.getPaymentMethodBreakdown(
                parseStartDate(startDate), parseEndDate(endDate)));
    }
}
