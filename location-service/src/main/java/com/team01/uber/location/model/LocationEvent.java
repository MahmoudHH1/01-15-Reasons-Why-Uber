package com.team01.uber.location.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "location_events")
public class LocationEvent {

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

    public String getId() { return id; }
    public Long getDriverId() { return driverId; }
    public String getAction() { return action; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Map<String, Object> getDetails() { return details; }
}
