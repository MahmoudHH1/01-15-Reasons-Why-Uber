package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverSearchResultDTO {

    private Long id;
    private String name;
    private String vehicleType;
    private String description;
    private Double rating;
    private String status;
}
