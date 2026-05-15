package com.team01.uber.contracts.dto;

public record DriverRideSummaryDTO(
        Long driverId,
        long totalRides,
        long completedRides,
        Double totalEarnings,
        Double averageFare
) {
    public static DriverRideSummaryDTO empty(Long driverId) {
        return new DriverRideSummaryDTO(driverId, 0L, 0L, 0.0, 0.0);
    }
}
