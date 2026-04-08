package com.team01.uber.user.dto;

public record TopRiderDTO(
        Long userId,
        String name,
        Double totalSpent,
        Long rideCount
) {}