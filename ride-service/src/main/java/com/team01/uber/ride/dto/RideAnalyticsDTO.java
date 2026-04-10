package com.team01.uber.ride.dto;

public record RideAnalyticsDTO(
        long totalRides,
        long completedRides,
        long cancelledRides,
        double totalRevenue,
        double averageFare,
        double completionRate
) {
}
