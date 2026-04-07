package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface RideRepository extends JpaRepository<Ride, Long> {
    @Query(value = "SELECT COUNT(*) FROM rides " +
            "WHERE pickup_latitude BETWEEN :lat - 0.01 AND :lat + 0.01 " +
            "AND pickup_longitude BETWEEN :lon - 0.01 AND :lon + 0.01 " +
            "AND status::text IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')",
            nativeQuery = true)
    long countActiveRidesNearby(@Param("lat") double lat, @Param("lon") double lon);


    @Query(value = "SELECT COUNT(*) > 0 FROM drivers WHERE id = :id AND status = 'BUSY'", nativeQuery = true)
    boolean isDriverBusy(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE drivers SET status = 'AVAILABLE' WHERE id = :id AND STATUS = 'BUSY'", nativeQuery = true)
    int setDriverAvailable(@Param("id") Long id);

    @Modifying
    @Query(value = "INSERT INTO payments (ride_id, user_id, amount, status, created_at) " +
            "VALUES (:rideId, :userId, :amount, CAST(:status AS payment_status), :createdAt)",
            nativeQuery = true)
    void createPayment(@Param("rideId") Long rideId,
                       @Param("userId") Long userId,
                       @Param("amount") Double amount,
                       @Param("status") String status,
                       @Param("createdAt") LocalDateTime createdAt);
}
