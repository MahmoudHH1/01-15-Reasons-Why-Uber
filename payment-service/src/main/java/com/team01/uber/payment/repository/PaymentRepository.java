package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query(value = "SELECT COUNT(*) FROM users WHERE id = :userId", nativeQuery = true)
    long countUsersById(@Param("userId") Long userId);

    @Query(value = "SELECT method, COUNT(*) AS cnt, SUM(amount) AS total " +
            "FROM payments " +
            "WHERE user_id = :userId AND status = 'COMPLETED' " +
            "GROUP BY method", nativeQuery = true)
    List<Object[]> findCompletedPaymentsSummaryByUser(@Param("userId") Long userId);
}
