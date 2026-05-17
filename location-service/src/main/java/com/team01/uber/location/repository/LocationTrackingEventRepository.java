package com.team01.uber.location.repository;

import com.team01.uber.location.model.LocationTrackingEvent;
import com.team01.uber.location.model.LocationTrackingEventKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface LocationTrackingEventRepository extends CassandraRepository<LocationTrackingEvent, LocationTrackingEventKey> {

    List<LocationTrackingEvent> findByKeyDriverId(Long driverId);

    Optional<LocationTrackingEvent> findTopByKeyDriverId(Long driverId);

    @Query("SELECT * FROM location_tracking_events WHERE driver_id = ?0 AND timestamp >= ?1 AND timestamp <= ?2")
    List<LocationTrackingEvent> findByDriverIdAndTimestampBetween(Long driverId, Instant startTime, Instant endTime);
}
