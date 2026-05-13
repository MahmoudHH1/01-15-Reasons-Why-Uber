package com.team01.uber.contracts.dto;

public record RideSummaryDTO(
        Long userId,
        long totalRides,
        long completedRides,
        long cancelledRides,
        Double totalSpent,
        Double averageFare
) {}