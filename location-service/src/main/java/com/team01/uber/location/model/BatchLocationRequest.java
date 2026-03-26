package com.team01.uber.location.model;

import java.util.List;

public class BatchLocationRequest {

    private Long driverId;
    private List<Location> locations;

    public Long getDriverId() { return driverId; }
    public void setDriverId(Long driverId) { this.driverId = driverId; }

    public List<Location> getLocations() { return locations; }
    public void setLocations(List<Location> locations) { this.locations = locations; }
}
