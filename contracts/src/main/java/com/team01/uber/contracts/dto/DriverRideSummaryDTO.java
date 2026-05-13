package com.team01.uber.contracts.dto;

public record DriverRideSummaryDTO(
        Long driverId,
        long totalRides,
        Double totalEarnings,
        Double averageFare
) {}