package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopDriverDTO {

    private Long driverId;
    private String name;
    private Double rating;
    private Long totalRides;
}
