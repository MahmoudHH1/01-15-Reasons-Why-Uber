package com.team01.uber.payment.controller;

import com.team01.uber.payment.model.PaymentCoupon;
import com.team01.uber.payment.service.PaymentCouponService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class PaymentCouponController {

    private final PaymentCouponService paymentCouponService;

    public PaymentCouponController(PaymentCouponService paymentCouponService) {
        this.paymentCouponService = paymentCouponService;
    }

    @GetMapping("/api/payment-coupons/{id}")
    public ResponseEntity<PaymentCoupon> getPaymentCouponById(@PathVariable Long id) {
        return ResponseEntity.ok(paymentCouponService.getPaymentCouponById(id));
    }

    @GetMapping("/api/payment-coupons")
    public ResponseEntity<List<PaymentCoupon>> getAllPaymentCoupons() {
        return ResponseEntity.ok(paymentCouponService.getAllPaymentCoupons());
    }

    @PutMapping("/api/payments/{paymentId}/coupons/{couponId}/{id}")
    public ResponseEntity<PaymentCoupon> updatePaymentCoupon(@PathVariable Long paymentId,
                                                             @PathVariable Long couponId,
                                                             @PathVariable Long id,
                                                             @Valid @RequestBody PaymentCoupon paymentCoupon) {
        return ResponseEntity.ok(paymentCouponService.updatePaymentCoupon(paymentId, couponId, id, paymentCoupon));
    }

    @DeleteMapping("/api/payment-coupons/{id}")
    public ResponseEntity<Void> deletePaymentCoupon(@PathVariable Long id) {
        paymentCouponService.deletePaymentCoupon(id);
        return ResponseEntity.noContent().build();
    }
}