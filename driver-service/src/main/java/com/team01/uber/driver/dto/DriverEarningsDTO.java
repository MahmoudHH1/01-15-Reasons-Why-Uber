package com.team01.uber.driver.dto;

public class DriverEarningsDTO {

    private Long driverId;
    private String name;
    private Long totalRides;
    private Double totalEarnings;
    private Double averageFare;

    public DriverEarningsDTO() {}

    public DriverEarningsDTO(Long driverId, String name, Long totalRides, Double totalEarnings, Double averageFare) {
        this.driverId = driverId;
        this.name = name;
        this.totalRides = totalRides;
        this.totalEarnings = totalEarnings;
        this.averageFare = averageFare;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getTotalRides() {
        return totalRides;
    }

    public void setTotalRides(Long totalRides) {
        this.totalRides = totalRides;
    }

    public Double getTotalEarnings() {
        return totalEarnings;
    }

    public void setTotalEarnings(Double totalEarnings) {
        this.totalEarnings = totalEarnings;
    }

    public Double getAverageFare() {
        return averageFare;
    }

    public void setAverageFare(Double averageFare) {
        this.averageFare = averageFare;
    }
}
