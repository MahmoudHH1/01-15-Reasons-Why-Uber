package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RideRepository extends JpaRepository<Ride, Long> {

    // Cross-service: queries the shared drivers table directly
    @Query(value = "SELECT COUNT(*) > 0 FROM drivers WHERE id = :id", nativeQuery = true)
    boolean driverExists(@Param("id") Long id);

    @Query(value = "SELECT COUNT(*) > 0 FROM drivers WHERE id = :id AND status = 'AVAILABLE'", nativeQuery = true)
    boolean isDriverAvailable(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE drivers SET status = 'BUSY' WHERE id = :id", nativeQuery = true)
    void setDriverBusy(@Param("id") Long id);
}
