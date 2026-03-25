package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.enums.RideStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RideRepository extends JpaRepository<Ride, Long> { }
