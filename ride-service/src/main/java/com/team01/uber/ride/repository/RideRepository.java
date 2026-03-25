package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.enums.RideStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> {

    List<Ride> findByUserId(Long userId);

    List<Ride> findByDriverId(Long driverId);

    List<Ride> findByStatus(RideStatus status);
}
