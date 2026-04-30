package com.team01.uber.location.repository;

import com.team01.uber.location.model.LocationTrackingEvent;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface LocationTrackingEventRepository extends CassandraRepository<LocationTrackingEvent, Long> {

    List<LocationTrackingEvent> findByDriverId(Long driverId);

    List<LocationTrackingEvent> findByDriverIdAndTimestampBetween(Long driverId, Instant startTime, Instant endTime);
}
