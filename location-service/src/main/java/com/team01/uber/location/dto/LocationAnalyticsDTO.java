package com.team01.uber.location.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * Location Analytics DTO satisfying the Builder Pattern requirement (DP-6/§3.5).
 * Also functions as a standard POJO to ensure seamless JSON serialization/deserialization 
 * with Redis and Jackson without requiring specific annotations.
 */
public class LocationAnalyticsDTO {
    private long totalLocationEvents;
    private long activeDrivers;
    private double averageSpeed;
    private Map<Integer, Long> eventsByHour = new HashMap<>();

    /**
     * Default constructor required for Jackson/Redis deserialization.
     */
    public LocationAnalyticsDTO() {
    }

    /**
     * Internal constructor used by the Builder.
     */
    private LocationAnalyticsDTO(Builder builder) {
        this.totalLocationEvents = builder.totalLocationEvents;
        this.activeDrivers = builder.activeDrivers;
        this.averageSpeed = builder.averageSpeed;
        this.eventsByHour = builder.eventsByHour;
    }

    // Getters and Setters (Required for Jackson compatibility)
    public long getTotalLocationEvents() { return totalLocationEvents; }
    public void setTotalLocationEvents(long totalLocationEvents) { this.totalLocationEvents = totalLocationEvents; }

    public long getActiveDrivers() { return activeDrivers; }
    public void setActiveDrivers(long activeDrivers) { this.activeDrivers = activeDrivers; }

    public double getAverageSpeed() { return averageSpeed; }
    public void setAverageSpeed(double averageSpeed) { this.averageSpeed = averageSpeed; }

    public Map<Integer, Long> getEventsByHour() { return eventsByHour; }
    public void setEventsByHour(Map<Integer, Long> eventsByHour) { this.eventsByHour = eventsByHour; }

    /**
     * Static entry point for the Builder pattern.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Concrete Builder class implementation.
     */
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
