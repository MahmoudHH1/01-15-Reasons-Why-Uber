package com.team01.uber.driver.repository;

import com.team01.uber.driver.model.Driver;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    Optional<Driver> findByEmail(String email);

    Optional<Driver> findByPhone(String phone);

    Optional<Driver> findByLicenseNumber(String licenseNumber);

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
