package com.team01.uber.location.repository;

import com.team01.uber.location.model.LocationTrackingEvent;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface LocationTrackingEventRepository extends CassandraRepository<LocationTrackingEvent, Long> {

    List<LocationTrackingEvent> findByDriverId(Long driverId);

    @Query("SELECT * FROM location_tracking_events WHERE driver_id = ?0 AND timestamp >= ?1 AND timestamp <= ?2")
    List<LocationTrackingEvent> findByDriverIdAndTimestampBetween(Long driverId, Instant startTime, Instant endTime);
}
