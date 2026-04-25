package com.team01.uber.location.repository;

import com.team01.uber.location.model.LocationTrackingEvent;
import com.team01.uber.location.model.LocationTrackingEventKey;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LocationTrackingRepository extends CassandraRepository<LocationTrackingEvent, LocationTrackingEventKey> {

    List<LocationTrackingEvent> findByKeyDriverId(Long driverId);
}
