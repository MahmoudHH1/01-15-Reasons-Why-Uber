package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByEmail(String email);

    Optional<Driver> findByPhone(String phone);

    Optional<Driver> findByLicenseNumber(String licenseNumber);

    @Query(value = """
            SELECT d.id, d.name, d.rating, d.total_ratings
            FROM drivers d
            ORDER BY d.rating DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopRatedDrivers(@Param("limit") int limit);

    @Query(value = "SELECT * FROM drivers WHERE vehicle_details->>'vehicleType' = :type", nativeQuery = true)
    List<Driver> findByVehicleType(@Param("type") String type);

    @Query(value = "SELECT * FROM drivers WHERE vehicle_details->>'vehicleType' = :type AND status = CAST(:status AS driver_status)", nativeQuery = true)
    List<Driver> findByVehicleTypeAndStatus(@Param("type") String type, @Param("status") String status);

    List<Driver> findByRatingBetweenOrderByRatingDesc(Double minRating, Double maxRating);

    List<Driver> findByStatusAndRatingBetweenOrderByRatingDesc(DriverStatus status, Double minRating, Double maxRating);
}

