package com.team01.uber.contracts.dto;

public record RideDTO(
        Long id,
        Long userId,
        Long driverId,
        String status,
        Double fare
) {}
