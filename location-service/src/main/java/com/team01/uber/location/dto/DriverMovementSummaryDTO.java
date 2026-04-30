package com.team01.uber.location.dto;

import java.time.LocalDateTime;

public class DriverMovementSummaryDTO {

    private final Long driverId;
    private final Long totalLocationPoints;
    private final Double averageSpeed;
    private final Double maxSpeed;
    private final LocalDateTime firstTimestamp;
    private final LocalDateTime lastTimestamp;

    private DriverMovementSummaryDTO(Builder builder) {
        this.driverId = builder.driverId;
        this.totalLocationPoints = builder.totalLocationPoints;
        this.averageSpeed = builder.averageSpeed;
        this.maxSpeed = builder.maxSpeed;
        this.firstTimestamp = builder.firstTimestamp;
        this.lastTimestamp = builder.lastTimestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getDriverId() { return driverId; }
    public Long getTotalLocationPoints() { return totalLocationPoints; }
    public Double getAverageSpeed() { return averageSpeed; }
    public Double getMaxSpeed() { return maxSpeed; }
    public LocalDateTime getFirstTimestamp() { return firstTimestamp; }
    public LocalDateTime getLastTimestamp() { return lastTimestamp; }

    public static class Builder {
        private Long driverId;
        private Long totalLocationPoints;
        private Double averageSpeed;
        private Double maxSpeed;
        private LocalDateTime firstTimestamp;
        private LocalDateTime lastTimestamp;

        public Builder driverId(Long driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder totalLocationPoints(Long totalLocationPoints) {
            this.totalLocationPoints = totalLocationPoints;
            return this;
        }

        public Builder averageSpeed(Double averageSpeed) {
            this.averageSpeed = averageSpeed;
            return this;
        }

        public Builder maxSpeed(Double maxSpeed) {
            this.maxSpeed = maxSpeed;
            return this;
        }

        public Builder firstTimestamp(LocalDateTime firstTimestamp) {
            this.firstTimestamp = firstTimestamp;
            return this;
        }

        public Builder lastTimestamp(LocalDateTime lastTimestamp) {
            this.lastTimestamp = lastTimestamp;
            return this;
        }

        public DriverMovementSummaryDTO build() {
            return new DriverMovementSummaryDTO(this);
        }
    }
}
