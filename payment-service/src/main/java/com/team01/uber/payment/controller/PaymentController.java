package com.team01.uber.payment.controller;

import com.team01.uber.payment.dto.PaymentDetailsDTO;
import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.team01.uber.payment.dto.ProcessPaymentRequest;
import com.team01.uber.payment.dto.RefundRequest;
import com.team01.uber.payment.model.PaymentStatus;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @GetMapping("/user/{userId}/summary")
    public UserPaymentSummaryDTO getUserPaymentSummary(@PathVariable Long userId) {
        return paymentService.getUserPaymentSummary(userId);
    }

    @PutMapping("/{id}/refund")
    public Payment refund(@PathVariable Long id, @Valid @RequestBody RefundRequest request) {
        return paymentService.processRefund(id, request.getReason());
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
    public ResponseEntity<Payment> updatePayment(@PathVariable Long id, @Valid @RequestBody Payment payment) {
        return ResponseEntity.ok(paymentService.updatePayment(id, payment));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePayment(@PathVariable Long id) {
        paymentService.deletePayment(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    public List<Payment> searchPayments(
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate
    ) {
        return paymentService.searchPayments(status, startDate.atStartOfDay(), endDate.atTime(23, 59, 59));
    }
    @PutMapping("/{id}/retry")
    public ResponseEntity<Payment> retryPayment(@PathVariable Long id) {
        return ResponseEntity.ok(paymentService.retryFailedPayment(id));
    }

    @GetMapping("/{paymentId}/details")
    public ResponseEntity<PaymentDetailsDTO> getPaymentDetails(@PathVariable Long paymentId) {
        return ResponseEntity.ok(paymentService.getPaymentDetails(paymentId));
    }

    @PostMapping("/ride/{rideId}")
    public ResponseEntity<Payment> processPaymentForRide(
            @PathVariable Long rideId,
            @Valid @RequestBody ProcessPaymentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentService.processPaymentForRide(rideId, request));
    }
}
