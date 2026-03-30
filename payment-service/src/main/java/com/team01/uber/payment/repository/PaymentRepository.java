package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Query(value = "SELECT status FROM rides WHERE id = :rideId", nativeQuery = true)
    String findRideStatusById(@Param("rideId") Long rideId);

    Optional<Payment> findByRideIdAndStatus(Long rideId, PaymentStatus status);

    boolean existsByRideIdAndStatus(Long rideId, PaymentStatus status);
}
