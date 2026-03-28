package com.team01.uber.location.dto;

import java.time.LocalDateTime;

public class DriverMovementSummaryDTO {

    private Long driverId;
    private Long totalLocationPoints;
    private Double averageSpeed;
    private Double maxSpeed;
    private LocalDateTime firstTimestamp;
    private LocalDateTime lastTimestamp;

    public DriverMovementSummaryDTO(Long driverId, Long totalLocationPoints, Double averageSpeed,
                                    Double maxSpeed, LocalDateTime firstTimestamp, LocalDateTime lastTimestamp) {
        this.driverId = driverId;
        this.totalLocationPoints = totalLocationPoints;
        this.averageSpeed = averageSpeed;
        this.maxSpeed = maxSpeed;
        this.firstTimestamp = firstTimestamp;
        this.lastTimestamp = lastTimestamp;
    }

    public Long getDriverId() { return driverId; }
    public Long getTotalLocationPoints() { return totalLocationPoints; }
    public Double getAverageSpeed() { return averageSpeed; }
    public Double getMaxSpeed() { return maxSpeed; }
    public LocalDateTime getFirstTimestamp() { return firstTimestamp; }
    public LocalDateTime getLastTimestamp() { return lastTimestamp; }
}
