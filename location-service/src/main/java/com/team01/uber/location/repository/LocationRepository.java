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
            SELECT l.driver_id, l.latitude, l.longitude,
                   SQRT(POWER(l.latitude - :lat, 2) + POWER(l.longitude - :lon, 2)) * 111 AS distance_km
            FROM locations l
            JOIN (
                SELECT driver_id, MAX(timestamp) AS latest
                FROM locations
                GROUP BY driver_id
            ) latest_loc ON l.driver_id = latest_loc.driver_id AND l.timestamp = latest_loc.latest
            WHERE SQRT(POWER(l.latitude - :lat, 2) + POWER(l.longitude - :lon, 2)) * 111 <= :radiusKm
            ORDER BY distance_km ASC
            LIMIT 100
            """, nativeQuery = true)
    List<Object[]> findNearbyDriversLocal(@Param("lat") Double lat,
                                         @Param("lon") Double lon,
                                         @Param("radiusKm") Double radiusKm);

    @Query(value = "SELECT * FROM locations WHERE metadata->>:key = :value", nativeQuery = true)
    List<Location> findByMetadataKeyEq(@Param("key") String key, @Param("value") String value);

    @Query(value = "SELECT * FROM locations WHERE (metadata->>:key)::numeric > CAST(:value AS numeric)", nativeQuery = true)
    List<Location> findByMetadataKeyGt(@Param("key") String key, @Param("value") String value);

    @Query(value = "SELECT * FROM locations WHERE (metadata->>:key)::numeric < CAST(:value AS numeric)", nativeQuery = true)
    List<Location> findByMetadataKeyLt(@Param("key") String key, @Param("value") String value);

    @Query(value = "SELECT * FROM locations WHERE timestamp BETWEEN :startDate AND :endDate ORDER BY timestamp ASC", nativeQuery = true)
    List<Location> findInDateRange(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Query(value = "SELECT * FROM locations WHERE timestamp BETWEEN :startDate AND :endDate AND driver_id = :driverId ORDER BY timestamp ASC", nativeQuery = true)
    List<Location> findInDateRangeByDriver(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate, @Param("driverId") Long driverId);

    @Query(value = """
            SELECT
                COUNT(*)                                                                AS total_location_points,
                COALESCE(AVG(NULLIF(metadata->>'speed', '')::numeric), 0)              AS average_speed,
                MAX(NULLIF(metadata->>'speed', '')::numeric)                           AS max_speed,
                MIN(timestamp)                                                          AS first_timestamp,
                MAX(timestamp)                                                          AS last_timestamp
            FROM locations
            WHERE driver_id = :driverId
              AND timestamp BETWEEN :startDate AND :endDate
            """, nativeQuery = true)
    List<Object[]> getMovementSummary(@Param("driverId") Long driverId,
                                      @Param("startDate") LocalDateTime startDate,
                                      @Param("endDate") LocalDateTime endDate);

    @Query(value = """
            SELECT l.driver_id, l.latitude, l.longitude,
                   (l.metadata->>'speed')::numeric AS last_speed,
                   l.timestamp AS last_updated
            FROM locations l
            JOIN (
                SELECT driver_id, MAX(timestamp) AS latest
                FROM locations
                GROUP BY driver_id
            ) latest_loc ON l.driver_id = latest_loc.driver_id AND l.timestamp = latest_loc.latest
            WHERE (l.metadata->>'speed')::numeric <= :maxSpeed
              AND l.timestamp >= :since
            ORDER BY l.timestamp ASC
            LIMIT 100
            """, nativeQuery = true)
    List<Object[]> findStationaryDriversLocal(@Param("maxSpeed") Double maxSpeed,
                                              @Param("since") LocalDateTime since);

    @Query(value = """
            SELECT
                COUNT(*)                                                        AS total_location_events,
                COUNT(DISTINCT driver_id)                                       AS active_drivers,
                COALESCE(AVG(NULLIF(metadata->>'speed', '')::numeric), 0)      AS average_speed
            FROM locations
            WHERE timestamp BETWEEN :startDate AND :endDate
            """, nativeQuery = true)
    List<Object[]> getDashboardStats(@Param("startDate") LocalDateTime startDate,
                                     @Param("endDate") LocalDateTime endDate);

    @Query(value = """
            SELECT EXTRACT(HOUR FROM timestamp) AS hour, COUNT(*) AS count
            FROM locations
            WHERE timestamp BETWEEN :startDate AND :endDate
            GROUP BY hour
            ORDER BY hour
            """, nativeQuery = true)
    List<Object[]> getEventsByHour(@Param("startDate") LocalDateTime startDate,
                                   @Param("endDate") LocalDateTime endDate);

}
