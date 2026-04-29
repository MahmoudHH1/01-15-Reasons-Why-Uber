package com.team01.uber.location.dto;

import java.time.Instant;

public class LocationTrackingDTO {

    private Instant timestamp;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private Double heading;
    private Double accuracy;
    private Long rideId;
    private String notes;

    public LocationTrackingDTO(Instant timestamp, Double latitude, Double longitude,
                               Double speed, Double heading, Double accuracy,
                               Long rideId, String notes) {
        this.timestamp = timestamp;
        this.latitude = latitude;
        this.longitude = longitude;
        this.speed = speed;
        this.heading = heading;
        this.accuracy = accuracy;
        this.rideId = rideId;
        this.notes = notes;
    }

    public Instant getTimestamp() { return timestamp; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Double getSpeed() { return speed; }
    public Double getHeading() { return heading; }
    public Double getAccuracy() { return accuracy; }
    public Long getRideId() { return rideId; }
    public String getNotes() { return notes; }
}
