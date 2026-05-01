package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverEventDTO {

    private String id;
    private Long driverId;
    private String action;
    private LocalDateTime timestamp;
    private Map<String, Object> details;
}
