package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByEmail(String email);

    Optional<Driver> findByPhone(String phone);

    Optional<Driver> findByLicenseNumber(String licenseNumber);

    List<Driver> findByRatingBetweenOrderByRatingDesc(Double minRating, Double maxRating);

    List<Driver> findByStatusAndRatingBetweenOrderByRatingDesc(DriverStatus status, Double minRating, Double maxRating);
}
