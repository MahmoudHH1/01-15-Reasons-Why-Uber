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

    @Query(value = """
            SELECT d.id, d.name, d.rating, COUNT(r.id) AS total_rides
            FROM drivers d
            LEFT JOIN rides r ON r.driver_id = d.id AND r.status = 'COMPLETED'
            GROUP BY d.id, d.name, d.rating
            ORDER BY d.rating DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTopRatedDrivers(@Param("limit") int limit);
}
