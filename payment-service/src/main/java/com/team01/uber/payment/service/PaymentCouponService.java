package com.team01.uber.payment.service;

import com.team01.uber.payment.model.Coupon;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentCoupon;
import com.team01.uber.payment.repository.CouponRepository;
import com.team01.uber.payment.repository.PaymentCouponRepository;
import com.team01.uber.payment.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class PaymentCouponService {

    private final PaymentCouponRepository paymentCouponRepository;
    private final PaymentRepository paymentRepository;
    private final CouponRepository couponRepository;

    public PaymentCouponService(PaymentCouponRepository paymentCouponRepository,
                                PaymentRepository paymentRepository,
                                CouponRepository couponRepository) {
        this.paymentCouponRepository = paymentCouponRepository;
        this.paymentRepository = paymentRepository;
        this.couponRepository = couponRepository;
    }

    public PaymentCoupon createPaymentCoupon(Long paymentId, Long couponId, PaymentCoupon paymentCoupon) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found"));
        paymentCoupon.setPayment(payment);
        paymentCoupon.setCoupon(coupon);
        return paymentCouponRepository.save(paymentCoupon);
    }

    public PaymentCoupon getPaymentCouponById(Long id) {
        return paymentCouponRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentCoupon not found"));
    }

    public List<PaymentCoupon> getAllPaymentCoupons() {
        return paymentCouponRepository.findAll();
    }

    public PaymentCoupon updatePaymentCoupon(Long paymentId, Long couponId, Long id, PaymentCoupon paymentCoupon) {
        PaymentCoupon existing = getPaymentCouponById(id);
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Coupon not found"));
        existing.setDiscountApplied(paymentCoupon.getDiscountApplied());
        existing.setAppliedAt(paymentCoupon.getAppliedAt());
        existing.setPayment(payment);
        existing.setCoupon(coupon);
        return paymentCouponRepository.save(existing);
    }

    public void deletePaymentCoupon(Long id) {
        if (!paymentCouponRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentCoupon not found");
        }
        paymentCouponRepository.deleteById(id);
    }
}
