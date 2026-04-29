package com.team01.uber.location.dto;

import java.time.LocalDateTime;

public class DriverMovementSummaryDTO {

    private Long driverId;
    private Long totalLocationPoints;
    private Double averageSpeed;
    private Double maxSpeed;
    private LocalDateTime firstTimestamp;
    private LocalDateTime lastTimestamp;

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
}
