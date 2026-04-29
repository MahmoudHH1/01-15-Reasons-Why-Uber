package com.team01.uber.location.dto;

public class NearbyDriverDTO {

    private Long driverId;
    private String driverName;
    private Double latitude;
    private Double longitude;
    private Double distanceKm;

    public NearbyDriverDTO(Long driverId, String driverName, Double latitude, Double longitude, Double distanceKm) {
        this.driverId = driverId;
        this.driverName = driverName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.distanceKm = distanceKm;
    }

    public Long getDriverId() { return driverId; }
    public String getDriverName() { return driverName; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getDistanceKm() { return distanceKm; }
}
