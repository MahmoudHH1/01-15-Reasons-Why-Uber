package com.team01.uber.payment.controller;

import com.team01.uber.payment.dto.CouponUsageDTO;
import com.team01.uber.payment.dto.PaymentDetailsDTO;
import com.team01.uber.payment.dto.PaymentMethodDTO;
import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.dto.RevenueReportDTO;
import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.service.CouponService;
import com.team01.uber.payment.service.PaymentCouponService;
import com.team01.uber.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.team01.uber.payment.dto.PaymentWithCouponsDTO;
import com.team01.uber.payment.dto.ProcessPaymentRequest;
import com.team01.uber.payment.dto.RefundRequest;
import com.team01.uber.payment.model.PaymentStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

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
    public UserPaymentSummaryDTO getUserPaymentSummary(@PathVariable Long userId) {
        return paymentService.getUserPaymentSummary(userId);
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
    public ResponseEntity<Payment> createPayment(@RequestBody Payment payment) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.createPayment(payment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> getPaymentById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.getPaymentById(id));
    }

    @GetMapping
    public ResponseEntity<List<Payment>> getAllPayments() {
        return ResponseEntity.ok(paymentService.getAllPayments());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Payment> updatePayment(@PathVariable Long id, @RequestBody Payment payment) {
        return ResponseEntity.ok(paymentService.updatePayment(id, payment));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
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
            @RequestParam String startDate,
            @RequestParam String endDate
    ) {
        return paymentService.searchPayments(status, parseStartDate(startDate), parseEndDate(endDate));
    }

    private LocalDateTime parseStartDate(String dateStr) {
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
            @RequestParam(name = "simulateFailure", required = false, defaultValue = "false") boolean simulateFailure) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.processPaymentForRide(rideId, request, simulateFailure));
    }

    @GetMapping("/analytics/methods")
    public ResponseEntity<List<PaymentMethodDTO>> getPaymentMethodBreakdown(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        return ResponseEntity.ok(paymentService.getPaymentMethodBreakdown(
                parseStartDate(startDate), parseEndDate(endDate)));
    }
}
