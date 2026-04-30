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

    @Query(value = "SELECT COUNT(*) FROM users WHERE id = :userId", nativeQuery = true)
    long countUsersById(@Param("userId") Long userId);

    @Query(value = "SELECT method::text, COUNT(*) AS cnt, SUM(amount) AS total " +
            "FROM payments " +
            "WHERE user_id = :userId AND status = 'COMPLETED' " +
            "GROUP BY method", nativeQuery = true)
    List<Object[]> findCompletedPaymentsSummaryByUser(@Param("userId") Long userId);

    @Query(value = "SELECT * FROM payments WHERE (:status IS NULL OR status::text = :status) " +
            "AND created_at BETWEEN :startDate AND :endDate " +
            "ORDER BY created_at DESC", nativeQuery = true)
    List<Payment> findByStatusAndDateRange(
            @Param("status") String status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
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

    @Query(value = "SELECT status FROM rides WHERE id = :rideId", nativeQuery = true)
    String findRideStatusById(@Param("rideId") Long rideId);

    @Query(value = "SELECT fare FROM rides WHERE id = :rideId", nativeQuery = true)
    Double findRideFareById(@Param("rideId") Long rideId);

    @Query(value = "SELECT user_id FROM rides WHERE id = :rideId", nativeQuery = true)
    Long findRideUserIdById(@Param("rideId") Long rideId);

    @Query(value = "SELECT (metadata->>'surgeMultiplier')::numeric FROM rides WHERE id = :rideId", nativeQuery = true)
    Double findRideSurgeMultiplierById(@Param("rideId") Long rideId);

    Optional<Payment> findByRideIdAndStatus(Long rideId, PaymentStatus status);

    boolean existsByRideIdAndStatus(Long rideId, PaymentStatus status);
}
