package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDate;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByEmail(String email);

    Optional<Driver> findByPhone(String phone);

    Optional<Driver> findByLicenseNumber(String licenseNumber);

    @Query(value = """
            SELECT d.id, d.name, d.rating, COUNT(r.id) AS total_rides
            FROM drivers d
            LEFT JOIN rides r ON r.driver_id = d.id AND r.status = 'COMPLETED'
            GROUP BY d.id, d.name, d.rating
            ORDER BY d.rating DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopRatedDrivers(@Param("limit") int limit);
    @Query(value = "SELECT * FROM drivers WHERE vehicle_details->>'type' = :type", nativeQuery = true)
    List<Driver> findByVehicleType(@Param("type") String type);

    @Query(value = "SELECT * FROM drivers WHERE vehicle_details->>'type' = :type AND status = CAST(:status AS driverstatus)", nativeQuery = true)
    List<Driver> findByVehicleTypeAndStatus(@Param("type") String type, @Param("status") String status);
    List<Driver> findByRatingBetweenOrderByRatingDesc(Double minRating, Double maxRating);

    List<Driver> findByStatusAndRatingBetweenOrderByRatingDesc(DriverStatus status, Double minRating, Double maxRating);
    @Query(value = "SELECT COUNT(*) FROM rides WHERE driver_id = :driverId " +
                   "AND status::text IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')",
           nativeQuery = true)
    long countActiveRidesByDriverId(@Param("driverId") Long driverId);

    @Query(value = "SELECT COUNT(*), COALESCE(SUM(fare), 0), COALESCE(AVG(fare), 0) " +
                   "FROM rides WHERE driver_id = :driverId " +
                   "AND status::text = 'COMPLETED' " +
                   "AND CAST(completed_at AS date) BETWEEN :startDate AND :endDate",
           nativeQuery = true)
    Object[] getEarningsSummary(@Param("driverId") Long driverId,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);
}
