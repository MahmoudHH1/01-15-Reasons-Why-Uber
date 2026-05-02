package com.team01.uber.ride.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.team01.uber.ride.enums.RideStatus;

import java.util.Map;

@JsonDeserialize(builder = RideAnalyticsDashboardDTO.Builder.class)
public class RideAnalyticsDashboardDTO {
    private final long totalRides;
    private final double totalRevenue;
    private final double averageRideFare;
    private final double completionRate;
    private final Map<RideStatus, Long> ridesByStatus;

    private RideAnalyticsDashboardDTO(Builder builder) {
        this.totalRides = builder.totalRides;
        this.totalRevenue = builder.totalRevenue;
        this.averageRideFare = builder.averageRideFare;
        this.completionRate = builder.completionRate;
        this.ridesByStatus = builder.ridesByStatus;
    }

    // Getters
    public long getTotalRides() { return totalRides; }
    public double getTotalRevenue() { return totalRevenue; }
    public double getAverageRideFare() { return averageRideFare; }
    public double getCompletionRate() { return completionRate; }
    public Map<RideStatus, Long> getRidesByStatus() { return ridesByStatus; }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private long totalRides;
        private double totalRevenue;
        private double averageRideFare;
        private double completionRate;
        private Map<RideStatus, Long> ridesByStatus;

        public Builder totalRides(long totalRides) { this.totalRides = totalRides; return this; }
        public Builder totalRevenue(double totalRevenue) { this.totalRevenue = totalRevenue; return this; }
        public Builder averageRideFare(double averageRideFare) { this.averageRideFare = averageRideFare; return this; }
        public Builder completionRate(double completionRate) { this.completionRate = completionRate; return this; }
        public Builder ridesByStatus(Map<RideStatus, Long> ridesByStatus) { this.ridesByStatus = ridesByStatus; return this; }

        public RideAnalyticsDashboardDTO build() {
            return new RideAnalyticsDashboardDTO(this);
        }
    }
}