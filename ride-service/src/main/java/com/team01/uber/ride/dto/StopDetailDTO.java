package com.team01.uber.ride.dto;

import com.team01.uber.ride.enums.RideStopStatus;

import java.util.Map;

public record StopDetailDTO(
        Long id,
        Integer stopOrder,
        String address,
        Double latitude,
        Double longitude,
        RideStopStatus status,
        Map<String, Object> metadata
) {}
