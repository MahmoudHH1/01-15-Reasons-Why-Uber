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

    @Query(value = "SELECT COUNT(*) FROM rides WHERE driver_id = :driverId " +
                   "AND status IN (CAST('REQUESTED' AS ridestatus), " +
                   "CAST('ACCEPTED' AS ridestatus), " +
                   "CAST('IN_PROGRESS' AS ridestatus))",
           nativeQuery = true)
    long countActiveRidesByDriverId(@Param("driverId") Long driverId);
}
