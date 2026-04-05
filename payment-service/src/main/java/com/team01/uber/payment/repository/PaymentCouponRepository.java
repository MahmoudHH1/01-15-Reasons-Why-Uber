package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.PaymentCoupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCouponRepository extends JpaRepository<PaymentCoupon, Long> {

    boolean existsByPayment_IdAndCoupon_Id(Long paymentId, Long couponId);
}
