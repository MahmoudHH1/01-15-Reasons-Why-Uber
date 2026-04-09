package com.team01.uber.user.dto;

public class UserRideSummaryDTO {

    private Long userId;
    private String name;
    private Long totalRides;
    private Long completedRides;
    private Long cancelledRides;
    private Double totalSpent;
    private Double averageFare;

    public UserRideSummaryDTO(Long userId, String name, Long totalRides, Long completedRides, Long cancelledRides, Double totalSpent, Double averageFare) {
        this.userId = userId;
        this.name = name;
        this.totalRides = totalRides;
        this.completedRides = completedRides;
        this.cancelledRides = cancelledRides;
        this.totalSpent = totalSpent;
        this.averageFare = averageFare;
    }

    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public Long getTotalRides() { return totalRides; }
    public Long getCompletedRides() { return completedRides; }
    public Long getCancelledRides() { return cancelledRides; }
    public Double getTotalSpent() { return totalSpent; }
    public Double getAverageFare() { return averageFare; }
}