package com.team01.uber.location.model;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "location_events")
public class LocationEvent implements MongoEvent {

    @Id
    private String id;

    private Long driverId;

    private String action;

    private LocalDateTime timestamp;

    private Map<String, Object> details;

    public LocationEvent() {}

    public LocationEvent(Long driverId, String action, LocalDateTime timestamp, Map<String, Object> details) {
        this.driverId = driverId;
        this.action = action;
        this.timestamp = timestamp;
        this.details = details;
    }

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }

    @Override
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    @Override
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    @Override
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
