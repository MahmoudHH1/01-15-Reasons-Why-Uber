package com.team01.uber.location.dto;

public class NearbyDriverDTO {

    private final Long driverId;
    private final String driverName;
    private final Double latitude;
    private final Double longitude;
    private final Double distanceKm;

    private NearbyDriverDTO(Builder builder) {
        this.driverId = builder.driverId;
        this.driverName = builder.driverName;
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.distanceKm = builder.distanceKm;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getDistanceKm() { return distanceKm; }

    public static class Builder {
        private Long driverId;
        private String driverName;
        private Double latitude;
        private Double longitude;
        private Double distanceKm;

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

        public Builder distanceKm(Double distanceKm) {
            this.distanceKm = distanceKm;
            return this;
        }

        public NearbyDriverDTO build() {
            return new NearbyDriverDTO(this);
        }
    }
}