package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {

    @Query(value = "SELECT COUNT(*) FROM rides " +
            "WHERE pickup_latitude BETWEEN :lat - 0.01 AND :lat + 0.01 " +
            "AND pickup_longitude BETWEEN :lon - 0.01 AND :lon + 0.01 " +
            "AND status::text IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')",
            nativeQuery = true)
    long countActiveRidesNearby(@Param("lat") double lat, @Param("lon") double lon);

    @Query(value = "SELECT COUNT(*) > 0 FROM rides r WHERE r.metadata ? :key", nativeQuery = true)
    boolean existsMetadataKey(@Param("key") String key);

    @Query(value = "SELECT * FROM rides WHERE metadata ->> :key = :value"
            , nativeQuery = true)
    List<Ride> findByMetadataField(@Param("key") String key, @Param("value") String value);
}
