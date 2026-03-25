package com.team01.uber.ride.repository;

import com.team01.uber.ride.model.Ride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RideRepository extends JpaRepository<Ride, Long> { }
