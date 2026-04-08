package com.team01.uber.ride.dto;

public record FareEstimateRequestDTO(
        Double pickupLatitude,
        Double pickupLongitude,
        Double dropoffLatitude,
        Double dropoffLongitude
) {}
