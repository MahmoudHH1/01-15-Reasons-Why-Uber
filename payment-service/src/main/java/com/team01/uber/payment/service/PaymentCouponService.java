package com.team01.uber.payment.service;

import com.team01.uber.payment.model.PaymentCoupon;
import com.team01.uber.payment.repository.PaymentCouponRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class PaymentCouponService {

    private final PaymentCouponRepository paymentCouponRepository;

    public PaymentCouponService(PaymentCouponRepository paymentCouponRepository) {
        this.paymentCouponRepository = paymentCouponRepository;
    }

    public PaymentCoupon createPaymentCoupon(PaymentCoupon paymentCoupon) {
        return paymentCouponRepository.save(paymentCoupon);
    }

    public PaymentCoupon getPaymentCouponById(Long id) {
        return paymentCouponRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PaymentCoupon not found"));
    }

    public List<PaymentCoupon> getAllPaymentCoupons() {
        return paymentCouponRepository.findAll();
    }

    public PaymentCoupon updatePaymentCoupon(Long id, PaymentCoupon paymentCoupon) {
        PaymentCoupon existing = getPaymentCouponById(id);
        existing.setDiscountApplied(paymentCoupon.getDiscountApplied());
        existing.setAppliedAt(paymentCoupon.getAppliedAt());
        existing.setPayment(paymentCoupon.getPayment());
        existing.setCoupon(paymentCoupon.getCoupon());
        return paymentCouponRepository.save(existing);
    }

    public void deletePaymentCoupon(Long id) {
        getPaymentCouponById(id);
        paymentCouponRepository.deleteById(id);
    }
}
