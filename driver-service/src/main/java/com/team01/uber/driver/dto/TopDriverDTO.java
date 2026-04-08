package com.team01.uber.driver.dto;

public class TopDriverDTO {

    private Long driverId;
    private String name;
    private Double rating;
    private Long totalRides;

    public TopDriverDTO(Long driverId, String name, Double rating, Long totalRides) {
        this.driverId = driverId;
        this.name = name;
        this.rating = rating;
        this.totalRides = totalRides;
    }

    public Long getDriverId() { return driverId; }
    public String getName() { return name; }
    public Double getRating() { return rating; }
    public Long getTotalRides() { return totalRides; }
}
