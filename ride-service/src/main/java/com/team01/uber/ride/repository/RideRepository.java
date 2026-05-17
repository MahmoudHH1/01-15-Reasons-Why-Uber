package com.team01.uber.ride.repository;

import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {

        // ── M1/M2 intra-service queries (unchanged) ───────────────────────────────

        // S3-F3: surge pricing — counts active rides near a location (local rides table
        // only)
        @Query(value = "SELECT COUNT(*) FROM rides " +
                        "WHERE pickup_latitude BETWEEN :lat - 0.01 AND :lat + 0.01 " +
                        "AND pickup_longitude BETWEEN :lon - 0.01 AND :lon + 0.01 " +
                        "AND status::text IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')", nativeQuery = true)
        long countActiveRidesNearby(@Param("lat") double lat, @Param("lon") double lon);

        // S3-F5: filter rides by metadata JSONB field (local rides table only)
        @Query(value = "SELECT * FROM rides WHERE metadata ->> :key = :value", nativeQuery = true)
        List<Ride> findByMetadataField(@Param("key") String key, @Param("value") String value);

        // S3-F1: rides by date range
        @Query("SELECT r FROM Ride r WHERE r.requestedAt >= :start AND r.requestedAt < :end ORDER BY r.requestedAt DESC")
        List<Ride> findByRequestedAtBetweenOrderByRequestedAtDesc(
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        // S3-F1: rides by date range and status
        @Query("SELECT r FROM Ride r WHERE r.requestedAt >= :start AND r.requestedAt < :end AND r.status = :status ORDER BY r.requestedAt DESC")
        List<Ride> findByRequestedAtBetweenAndStatusOrderByRequestedAtDesc(
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end,
                        @Param("status") RideStatus status);

        // S3-F1: flexible search
        @Query(value = "SELECT * FROM rides WHERE " +
                        "(CAST(:status AS text) IS NULL OR status::text = CAST(:status AS text)) AND " +
                        "(CAST(:start AS timestamp) IS NULL OR requested_at >= CAST(:start AS timestamp)) AND " +
                        "(CAST(:end AS timestamp) IS NULL OR requested_at < CAST(:end AS timestamp)) AND " +
                        "(CAST(:userId AS bigint) IS NULL OR user_id = CAST(:userId AS bigint)) " +
                        "ORDER BY requested_at DESC", nativeQuery = true)
        List<Ride> searchRidesFlexible(
                        @Param("status") String status,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end,
                        @Param("userId") Long userId);

        // ── M3 new queries for exposed endpoints (S3-READ-DB) ────────────────────

        // GET /api/rides/user/{userId}/summary — used by S1-F3
        @Query("SELECT COUNT(r) FROM Ride r WHERE r.userId = :userId")
        long countTotalRidesByUserId(@Param("userId") Long userId);

        @Query("SELECT COUNT(r) FROM Ride r WHERE r.userId = :userId AND r.status IN (:statuses)")
        long countRidesByUserIdAndStatuses(
                        @Param("userId") Long userId,
                        @Param("statuses") List<RideStatus> statuses);

        @Query("SELECT COALESCE(SUM(r.fare), 0.0) FROM Ride r WHERE r.userId = :userId AND r.status IN (:statuses)")
        Double sumFareByUserIdAndStatuses(
                        @Param("userId") Long userId,
                        @Param("statuses") List<RideStatus> statuses);

        // GET /api/rides/user/{userId}/active-count — used by S1-F4
        // Active = REQUESTED, ACCEPTED, IN_PROGRESS, COMPLETED, PAYMENT_PENDING (per
        // §5)
        @Query("SELECT COUNT(r) FROM Ride r WHERE r.userId = :userId AND r.status IN (:statuses)")
        int countActiveRidesByUserId(
                        @Param("userId") Long userId,
                        @Param("statuses") List<RideStatus> statuses);

        // GET /api/rides/user/{userId}/completed-count — used by S1-F9
        // Completed = COMPLETED, PAID (per §5)
        @Query("SELECT COUNT(r) FROM Ride r WHERE r.userId = :userId AND r.status IN (:statuses)")
        long countCompletedRidesByUserId(
                        @Param("userId") Long userId,
                        @Param("statuses") List<RideStatus> statuses);

        // ── M3 driver-side queries — NO date range ────────────────────────────────

        @Query("SELECT COUNT(r) FROM Ride r WHERE r.driverId = :driverId AND r.status IN (:statuses)")
        long countRidesByDriverIdAndStatuses(
                        @Param("driverId") Long driverId,
                        @Param("statuses") List<RideStatus> statuses);

        @Query("SELECT COALESCE(SUM(r.fare), 0.0) FROM Ride r WHERE r.driverId = :driverId AND r.status IN (:statuses)")
        Double sumFareByDriverIdAndStatuses(
                        @Param("driverId") Long driverId,
                        @Param("statuses") List<RideStatus> statuses);

        // ── M3 driver-side queries — WITH date range ──────────────────────────────

        @Query("SELECT COUNT(r) FROM Ride r WHERE r.driverId = :driverId AND r.status IN (:statuses) AND r.requestedAt >= :start AND r.requestedAt < :end")
        long countRidesByDriverIdAndStatusesAndDateRange(
                        @Param("driverId") Long driverId,
                        @Param("statuses") List<RideStatus> statuses,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        @Query("SELECT COALESCE(SUM(r.fare), 0.0) FROM Ride r WHERE r.driverId = :driverId AND r.status IN (:statuses) AND r.requestedAt >= :start AND r.requestedAt < :end")
        Double sumFareByDriverIdAndStatusesAndDateRange(
                        @Param("driverId") Long driverId,
                        @Param("statuses") List<RideStatus> statuses,
                        @Param("start") LocalDateTime start,
                        @Param("end") LocalDateTime end);

        // GET /api/rides/driver/{driverId}/active-count — used by S2-F4
        @Query("SELECT COUNT(r) FROM Ride r WHERE r.driverId = :driverId AND r.status IN (:statuses)")
        int countActiveRidesByDriverId(
                        @Param("driverId") Long driverId,
                        @Param("statuses") List<RideStatus> statuses);

        // GET /api/rides/driver/{driverId}/completed-count — used by S2-F6
        @Query("SELECT COUNT(r) FROM Ride r WHERE r.driverId = :driverId AND r.status IN (:statuses)")
        long countCompletedRidesByDriverId(
                        @Param("driverId") Long driverId,
                        @Param("statuses") List<RideStatus> statuses);

        // ── M3 removed (cross-service — replaced by Feign or events) ─────────────
        // driverExists() → DriverServiceClient.getDriver()
        // userExists() → UserServiceClient.getUser()
        // isDriverAvailable() → DriverServiceClient.getDriverAvailability()
        // isDriverBusy() → DriverServiceClient.getDriverAvailability()
        // setDriverBusy() → ride.placed event consumed by driver-service
        // setDriverAvailable() → ride.completed/cancelled event consumed by
        // driver-service
        // createPayment() → ride.completed event consumed by payment-service
        // findUserNameById() → UserServiceClient.getUser().name()
        // findDriverNameById() → DriverServiceClient.getDriver().name()
        // findDriverVehicleTypeById() →
        // DriverServiceClient.getDriver().vehicleDetails()
        // getTotalRevenueForCompletedRidesFromPayments() → local rides.fare used
        // instead (§5 S3-F10 note)
}