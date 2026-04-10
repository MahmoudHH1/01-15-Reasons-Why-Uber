package com.team01.uber.ride.dto;

import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;

import java.util.List;

public record RideWithStopsDTO(
        Ride ride,
        List<RideStop> stops
) {}
