package com.team01.uber.contracts.dto;

public record DriverAvailabilityDTO(
        String status  // AVAILABLE | BUSY | OFFLINE
) {}