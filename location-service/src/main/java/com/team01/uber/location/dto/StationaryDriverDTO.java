package com.team01.uber.location.dto;

import java.time.LocalDateTime;

public class StationaryDriverDTO {

    private Long driverId;
    private String driverName;
    private Double latitude;
    private Double longitude;
    private Double lastSpeed;
    private LocalDateTime lastUpdated;

    public StationaryDriverDTO(Long driverId, String driverName, Double latitude, Double longitude,
                               Double lastSpeed, LocalDateTime lastUpdated) {
        this.driverId = driverId;
        this.driverName = driverName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.lastSpeed = lastSpeed;
        this.lastUpdated = lastUpdated;
    }

    public Long getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getLastSpeed() { return lastSpeed; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
}
