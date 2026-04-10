package com.team01.uber.ride.dto;

import com.team01.uber.ride.enums.RideStatus;

import java.util.List;
import java.util.Map;

public record RideDetailsDTO(
        Long rideId,
        Long userId,
        Long driverId,
        RideStatus status,
        Double fare,
        Map<String, Object> metadata,
        List<StopDetailDTO> stops,
        int totalStops,
        long completedStops
) {}
