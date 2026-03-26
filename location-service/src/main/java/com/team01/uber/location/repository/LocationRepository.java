package com.team01.uber.location.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.team01.uber.location.model.Location;

import java.time.LocalDateTime;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
// Inherits from JpaRepository:
    // save(location)        → create / update
    // findById(id)          → read by ID
    // findAll()             → read all
    // deleteById(id)        → delete by ID
    // existsById(id)        → exists check

    @Query(value = "SELECT COUNT(*) FROM locations WHERE timestamp < :cutoff", nativeQuery = true)
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM locations WHERE timestamp < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Query(value = "SELECT COUNT(*) FROM drivers WHERE id = :driverId", nativeQuery = true)
    long countDriverById(@Param("driverId") Long driverId);

}
