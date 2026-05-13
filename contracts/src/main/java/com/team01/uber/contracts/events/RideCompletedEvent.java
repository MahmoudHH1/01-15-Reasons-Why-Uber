package com.team01.uber.contracts.events;

public record RideCompletedEvent(Long rideId, Long userId, Long driverId, Double fare) {}
