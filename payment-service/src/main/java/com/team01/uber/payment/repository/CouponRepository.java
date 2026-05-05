package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Query(value = """
            SELECT c.id, c.code, c.discount_type::text, c.discount_value,
                   COUNT(pc.id) AS times_used,
                   COALESCE(SUM(pc.discount_applied), 0) AS total_discount_given,
                   c.active, c.expiry_date
            FROM coupons c
            JOIN payment_coupons pc ON c.id = pc.coupon_id
            GROUP BY c.id, c.code, c.discount_type, c.discount_value,
                     c.active, c.expiry_date
            ORDER BY COUNT(pc.id) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopUsedCoupons(@Param("limit") int limit);
}
