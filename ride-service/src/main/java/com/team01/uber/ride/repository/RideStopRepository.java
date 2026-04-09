package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.RideStop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RideStopRepository extends JpaRepository<RideStop, Long> {

    List<RideStop> findByRideId(Long rideId);

    Optional<RideStop> findByIdAndRideId(Long id, Long rideId);

    @Query(value = "SELECT MAX(stop_order) FROM ride_stops WHERE ride_id = :rideId", nativeQuery = true)
    Integer findMaxStopOrderByRideId(@Param("rideId") Long rideId);
}
