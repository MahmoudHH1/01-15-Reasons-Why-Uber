package com.team01.uber.location.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.team01.uber.location.model.Location;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LocationRepository extends JpaRepository<Location, Long> {
// Inherits from JpaRepository:
    // save(location)        → create / update
    // findById(id)          → read by ID
    // findAll()             → read all
    // deleteById(id)        → delete by ID
    // existsById(id)        → exists check

    Optional<Location> findTopByDriverIdOrderByTimestampDescIdDesc(Long driverId);

    @Query(value = "SELECT COUNT(*) FROM locations WHERE timestamp < :cutoff", nativeQuery = true)
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query(value = "DELETE FROM locations WHERE timestamp < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    @Query(value = """
            SELECT d.id as driver_id, d.name as driver_name, l.latitude, l.longitude,
                   SQRT(POWER(l.latitude - :lat, 2) + POWER(l.longitude - :lon, 2)) * 111 AS distance_km
            FROM locations l
            JOIN (
                SELECT driver_id, MAX(timestamp) AS latest
                FROM locations
                GROUP BY driver_id
            ) latest_loc ON l.driver_id = latest_loc.driver_id AND l.timestamp = latest_loc.latest
            JOIN drivers d ON l.driver_id = d.id
            WHERE d.status = 'AVAILABLE'
              AND SQRT(POWER(l.latitude - :lat, 2) + POWER(l.longitude - :lon, 2)) * 111 <= :radiusKm
            ORDER BY distance_km ASC
            """, nativeQuery = true)
    List<Object[]> findNearbyAvailableDrivers(@Param("lat") Double lat,
                                              @Param("lon") Double lon,
                                              @Param("radiusKm") Double radiusKm);

    @Query(value = "SELECT COUNT(*) FROM drivers WHERE id = :driverId", nativeQuery = true)
    long countDriverById(@Param("driverId") Long driverId);

}
