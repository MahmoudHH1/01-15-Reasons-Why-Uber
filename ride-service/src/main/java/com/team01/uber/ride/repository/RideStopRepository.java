package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.RideStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideStopRepository extends JpaRepository<RideStop, Long> {

    List<RideStop> findByRideId(Long rideId);
}
