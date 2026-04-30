package com.team01.uber.location.dto;

import java.time.LocalDateTime;

public class StationaryDriverDTO {

    private final Long driverId;
    private final String driverName;
    private final Double latitude;
    private final Double longitude;
    private final Double lastSpeed;
    private final LocalDateTime lastUpdated;

    private StationaryDriverDTO(Builder builder) {
        this.driverId = builder.driverId;
        this.driverName = builder.driverName;
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.lastSpeed = builder.lastSpeed;
        this.lastUpdated = builder.lastUpdated;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getLastSpeed() { return lastSpeed; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }

    public static class Builder {
        private Long driverId;
        private String driverName;
        private Double latitude;
        private Double longitude;
        private Double lastSpeed;
        private LocalDateTime lastUpdated;

        public Builder driverId(Long driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder driverName(String driverName) {
            this.driverName = driverName;
            return this;
        }

        public Builder latitude(Double latitude) {
            this.latitude = latitude;
            return this;
        }

        public Builder longitude(Double longitude) {
            this.longitude = longitude;
            return this;
        }

        public Builder lastSpeed(Double lastSpeed) {
            this.lastSpeed = lastSpeed;
            return this;
        }

        public Builder lastUpdated(LocalDateTime lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        public StationaryDriverDTO build() {
            return new StationaryDriverDTO(this);
        }
    }
}