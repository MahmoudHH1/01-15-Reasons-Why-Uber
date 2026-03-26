package com.team01.uber.location.dto;

import java.util.List;

import com.team01.uber.location.model.Location;

public class BatchLocationRequest {

    private Long driverId;
    private List<Location> locations;

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }

    public List<Location> getLocations() { return locations; }
    public void setLocations(List<Location> locations) { this.locations = locations; }
}
