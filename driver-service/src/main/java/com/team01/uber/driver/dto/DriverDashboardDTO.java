package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDashboardDTO {

    private Long driverId;
    private String name;
    private Long totalRides;
    private Double totalEarnings;
    private Double averageRideFare;
    private Double averageRating;
    private Integer totalRatings;
}
