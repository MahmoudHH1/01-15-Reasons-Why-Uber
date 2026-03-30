package com.team01.uber.payment.controller;

import com.team01.uber.payment.model.PaymentCoupon;
import com.team01.uber.payment.service.PaymentCouponService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payment-coupons")
public class PaymentCouponController {

    private final PaymentCouponService paymentCouponService;

    public PaymentCouponController(PaymentCouponService paymentCouponService) {
        this.paymentCouponService = paymentCouponService;
    }

    @PostMapping
    public ResponseEntity<PaymentCoupon> createPaymentCoupon(@Valid @RequestBody PaymentCoupon paymentCoupon) {
        return ResponseEntity.status(HttpStatus.CREATED).body(paymentCouponService.createPaymentCoupon(paymentCoupon));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentCoupon> getPaymentCouponById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentCouponService.getPaymentCouponById(id));
    }

    @GetMapping
    public ResponseEntity<List<PaymentCoupon>> getAllPaymentCoupons() {
        return ResponseEntity.ok(paymentCouponService.getAllPaymentCoupons());
    }

    @PutMapping("/{id}")
    public ResponseEntity<PaymentCoupon> updatePaymentCoupon(@PathVariable Long id, @Valid @RequestBody PaymentCoupon paymentCoupon) {
        return ResponseEntity.ok(paymentCouponService.updatePaymentCoupon(id, paymentCoupon));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePaymentCoupon(@PathVariable Long id) {
        paymentCouponService.deletePaymentCoupon(id);
        return ResponseEntity.noContent().build();
    }
}
