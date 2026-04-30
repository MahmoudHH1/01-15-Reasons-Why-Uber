package com.team01.uber.location.dto;

import java.util.Map;

public class LocationAnalyticsDTO {
    private final long totalLocationEvents;
    private final long activeDrivers;
    private final double averageSpeed;
    private final Map<Integer, Long> eventsByHour;

    private LocationAnalyticsDTO(Builder builder) {
        this.totalLocationEvents = builder.totalLocationEvents;
        this.activeDrivers = builder.activeDrivers;
        this.averageSpeed = builder.averageSpeed;
        this.eventsByHour = builder.eventsByHour;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long getTotalLocationEvents() { return totalLocationEvents; }
    public long getActiveDrivers() { return activeDrivers; }
    public double getAverageSpeed() { return averageSpeed; }
    public Map<Integer, Long> getEventsByHour() { return eventsByHour; }

    public static class Builder {
        private long totalLocationEvents;
        private long activeDrivers;
        private double averageSpeed;
        private Map<Integer, Long> eventsByHour;

        public Builder totalLocationEvents(long totalLocationEvents) {
            this.totalLocationEvents = totalLocationEvents;
            return this;
        }

        public Builder activeDrivers(long activeDrivers) {
            this.activeDrivers = activeDrivers;
            return this;
        }

        public Builder averageSpeed(double averageSpeed) {
            this.averageSpeed = averageSpeed;
            return this;
        }

        public Builder eventsByHour(Map<Integer, Long> eventsByHour) {
            this.eventsByHour = eventsByHour;
            return this;
        }

        public LocationAnalyticsDTO build() {
            return new LocationAnalyticsDTO(this);
        }
    }
}
