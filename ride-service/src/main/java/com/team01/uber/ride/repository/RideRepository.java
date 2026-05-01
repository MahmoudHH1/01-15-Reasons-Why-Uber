package com.team01.uber.ride.repository;

import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {
    // Cross-service: queries the shared drivers table directly
    @Query(value = "SELECT COUNT(*) > 0 FROM drivers WHERE id = :id", nativeQuery = true)
    boolean driverExists(@Param("id") Long id);

    @Query(value = "SELECT COUNT(*) > 0 FROM drivers WHERE id = :id AND status = 'AVAILABLE'", nativeQuery = true)
    boolean isDriverAvailable(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE drivers SET status = 'BUSY' WHERE id = :id AND STATUS = 'AVAILABLE'", nativeQuery = true)
    int setDriverBusy(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE drivers SET status = 'AVAILABLE' WHERE id = :driverId", nativeQuery = true)
    int setDriverAvailable(@Param("driverId") Long driverId);

    @Query(value = "SELECT COUNT(*) FROM rides " +
            "WHERE pickup_latitude BETWEEN :lat - 0.01 AND :lat + 0.01 " +
            "AND pickup_longitude BETWEEN :lon - 0.01 AND :lon + 0.01 " +
            "AND status::text IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')",
            nativeQuery = true)
    long countActiveRidesNearby(@Param("lat") double lat, @Param("lon") double lon);


    @Query(value = "SELECT * FROM rides WHERE metadata ->> :key = :value"
            , nativeQuery = true)
    List<Ride> findByMetadataField(@Param("key") String key, @Param("value") String value);
  
  
    @Query("SELECT r FROM Ride r WHERE r.requestedAt >= :start AND r.requestedAt < :end ORDER BY r.requestedAt DESC")
    List<Ride> findByRequestedAtBetweenOrderByRequestedAtDesc(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query("SELECT r FROM Ride r WHERE r.requestedAt >= :start AND r.requestedAt < :end AND r.status = :status ORDER BY r.requestedAt DESC")
    List<Ride> findByRequestedAtBetweenAndStatusOrderByRequestedAtDesc(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("status") RideStatus status
    );


    @Query(value = "SELECT COUNT(*) > 0 FROM drivers WHERE id = :id AND status = 'BUSY'", nativeQuery = true)
    boolean isDriverBusy(@Param("id") Long id);


    @Modifying
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Query(value = "INSERT INTO payments (ride_id, user_id, amount, method, status, created_at) " +
            "VALUES (:rideId, :userId, :amount, 'CASH', 'PENDING', :createdAt)",
            nativeQuery = true)
    void createPayment(@Param("rideId") Long rideId,
                       @Param("userId") Long userId,
                       @Param("amount") Double amount,
                       @Param("createdAt") LocalDateTime createdAt);

    // S3-F11: cross-service lookup for user name
    @Query(value = "SELECT name FROM users WHERE id = :userId", nativeQuery = true)
    String findUserNameById(@Param("userId") Long userId);

    // S3-F11: cross-service lookup for driver name and vehicleType
    @Query(value = "SELECT name FROM drivers WHERE id = :driverId", nativeQuery = true)
    String findDriverNameById(@Param("driverId") Long driverId);

    @Query(value = "SELECT vehicle_details->>'vehicleType' FROM drivers WHERE id = :driverId", nativeQuery = true)
    String findDriverVehicleTypeById(@Param("driverId") Long driverId);
}
