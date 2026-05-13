package com.team01.uber.contracts.dto;

import java.util.Map;

public record DriverDTO(
        Long id,
        String name,
        String status,
        Map<String, Object> vehicleDetails
) {}
