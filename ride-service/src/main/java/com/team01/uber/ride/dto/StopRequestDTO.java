package com.team01.uber.ride.dto;

import java.util.Map;

public record StopRequestDTO(
        Integer stopOrder,
        Double latitude,
        Double longitude,
        String address,
        Map<String, Object> metadata
) {}
