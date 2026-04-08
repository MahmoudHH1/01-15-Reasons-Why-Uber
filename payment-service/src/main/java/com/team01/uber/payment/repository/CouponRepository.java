package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
}
