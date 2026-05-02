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

    public LocationTrackingDTO() {}

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

    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setSpeed(Double speed) { this.speed = speed; }
    public void setHeading(Double heading) { this.heading = heading; }
    public void setAccuracy(Double accuracy) { this.accuracy = accuracy; }
    public void setRideId(Long rideId) { this.rideId = rideId; }
    public void setNotes(String notes) { this.notes = notes; }
}
