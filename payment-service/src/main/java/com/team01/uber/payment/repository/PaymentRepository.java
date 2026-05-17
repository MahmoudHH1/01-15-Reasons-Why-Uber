package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query(value = "SELECT method::text, COUNT(*) AS cnt, SUM(amount) AS total " +
            "FROM payments " +
            "WHERE user_id = :userId AND status = 'COMPLETED' " +
            "GROUP BY method", nativeQuery = true)
    List<Object[]> findCompletedPaymentsSummaryByUser(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM payments WHERE (:status IS NULL OR status::text = :status) " +
            "AND (CAST(:startDate AS timestamp) IS NULL OR created_at >= :startDate) " +
            "AND (CAST(:endDate AS timestamp) IS NULL OR created_at <= :endDate) " +
            "AND (:userId IS NULL OR user_id = :userId) " +
            "ORDER BY created_at DESC", nativeQuery = true)
    List<Payment> findByStatusAndDateRange(
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            @Param("userId") Long userId
    );

    @Query(value = "SELECT COALESCE(SUM(amount), 0), COUNT(*) FROM payments " +
            "WHERE status::text = 'COMPLETED' AND created_at BETWEEN :startDate AND :endDate",
            nativeQuery = true)
    List<Object[]> getCompletedRevenueInRange(@Param("startDate") LocalDateTime startDate,
                                              @Param("endDate") LocalDateTime endDate);

    @Query(value = "SELECT COALESCE(SUM(amount), 0), COUNT(*) FROM payments " +
            "WHERE status::text = 'REFUNDED' AND created_at BETWEEN :startDate AND :endDate",
            nativeQuery = true)
    List<Object[]> getRefundedAmountInRange(@Param("startDate") LocalDateTime startDate,
                                            @Param("endDate") LocalDateTime endDate);

    @Query(value = "SELECT * FROM payments WHERE status = 'COMPLETED' " +
            "AND created_at BETWEEN :start AND :end ORDER BY created_at DESC LIMIT 100",
            nativeQuery = true)
    List<Payment> findCompletedPaymentsInDateRange(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    Optional<Payment> findByRideIdAndStatus(Long rideId, PaymentStatus status);

    boolean existsByRideIdAndStatus(Long rideId, PaymentStatus status);

    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payments " +
            "WHERE user_id = :userId AND status::text = 'COMPLETED' " +
            "AND created_at >= :startDate AND created_at <= :endDate",
            nativeQuery = true)
    java.math.BigDecimal getUserPaymentTotal(
            @Param("userId") Long userId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
