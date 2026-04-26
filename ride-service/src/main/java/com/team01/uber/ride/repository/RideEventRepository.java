package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.RideEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface RideEventRepository extends MongoRepository<RideEvent, String> {
    List<RideEvent> findByRideId(Long rideId);
}
