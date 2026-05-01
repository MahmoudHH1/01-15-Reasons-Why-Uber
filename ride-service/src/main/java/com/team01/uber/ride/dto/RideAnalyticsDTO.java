package com.team01.uber.ride.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = RideAnalyticsDTO.Builder.class)
public class RideAnalyticsDTO {
    private final long totalRides;
    private final long completedRides;
    private final long cancelledRides;
    private final double totalRevenue;
    private final double averageFare;
    private final double completionRate;

    private RideAnalyticsDTO(Builder builder) {
        this.totalRides = builder.totalRides;
        this.completedRides = builder.completedRides;
        this.cancelledRides = builder.cancelledRides;
        this.totalRevenue = builder.totalRevenue;
        this.averageFare = builder.averageFare;
        this.completionRate = builder.completionRate;
    }

    public long getTotalRides() { return totalRides; }
    public long getCompletedRides() { return completedRides; }
    public long getCancelledRides() { return cancelledRides; }
    public double getTotalRevenue() { return totalRevenue; }
    public double getAverageFare() { return averageFare; }
    public double getCompletionRate() { return completionRate; }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private long totalRides;
        private long completedRides;
        private long cancelledRides;
        private double totalRevenue;
        private double averageFare;
        private double completionRate;

        public Builder totalRides(long totalRides) {
            this.totalRides = totalRides;
            return this;
        }

        public Builder completedRides(long completedRides) {
            this.completedRides = completedRides;
            return this;
        }

        public Builder cancelledRides(long cancelledRides) {
            this.cancelledRides = cancelledRides;
            return this;
        }

        public Builder totalRevenue(double totalRevenue) {
            this.totalRevenue = totalRevenue;
            return this;
        }

        public Builder averageFare(double averageFare) {
            this.averageFare = averageFare;
            return this;
        }

        public Builder completionRate(double completionRate) {
            this.completionRate = completionRate;
            return this;
        }

        public RideAnalyticsDTO build() {
            return new RideAnalyticsDTO(this);
        }
    }
}