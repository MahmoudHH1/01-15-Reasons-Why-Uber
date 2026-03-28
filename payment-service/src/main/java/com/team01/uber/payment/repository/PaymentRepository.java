package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query("SELECT p FROM Payment p WHERE (:status IS NULL OR p.status = :status) " +
            "AND p.createdAt BETWEEN :startDate AND :endDate " +
            "ORDER BY p.createdAt DESC")
    List<Payment> findByStatusAndDateRange(
            @Param("status") PaymentStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
