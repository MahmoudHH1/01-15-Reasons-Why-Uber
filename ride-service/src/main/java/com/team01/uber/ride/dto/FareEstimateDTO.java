package com.team01.uber.ride.dto;

public record FareEstimateDTO(
        double estimatedDistance,
        double estimatedDuration,
        double estimatedFare,
        double surgeMultiplier
) {}
