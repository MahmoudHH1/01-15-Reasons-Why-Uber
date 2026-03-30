package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByEmail(String email);

    Optional<Driver> findByPhone(String phone);

    Optional<Driver> findByLicenseNumber(String licenseNumber);

    @Query(value = "SELECT * FROM drivers WHERE vehicle_details->>'type' = :type", nativeQuery = true)
    List<Driver> findByVehicleType(@Param("type") String type);

    @Query(value = "SELECT * FROM drivers WHERE vehicle_details->>'type' = :type AND status = :status", nativeQuery = true)
    List<Driver> findByVehicleTypeAndStatus(@Param("type") String type, @Param("status") String status);
}
