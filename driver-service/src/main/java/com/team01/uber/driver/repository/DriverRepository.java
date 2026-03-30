package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByEmail(String email);

    Optional<Driver> findByPhone(String phone);

    Optional<Driver> findByLicenseNumber(String licenseNumber);

    @Query(value = "SELECT COUNT(*) > 0 FROM rides WHERE id = :rideId", nativeQuery = true)
    boolean rideExists(@Param("rideId") Long rideId);

    @Query(value = "SELECT COUNT(*) > 0 FROM rides WHERE id = :rideId AND driver_id = :driverId", nativeQuery = true)
    boolean rideBelongsToDriver(@Param("rideId") Long rideId, @Param("driverId") Long driverId);

    @Query(value = "SELECT status FROM rides WHERE id = :rideId", nativeQuery = true)
    String getRideStatus(@Param("rideId") Long rideId);
}

