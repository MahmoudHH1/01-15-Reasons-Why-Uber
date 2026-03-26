package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.RideStop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RideStopRepository extends JpaRepository<RideStop, Long> {

    List<RideStop> findByRideId(Long rideId);

    Optional<RideStop> findByIdAndRideId(Long id, Long rideId);
}
