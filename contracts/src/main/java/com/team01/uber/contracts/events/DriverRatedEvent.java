package com.team01.uber.contracts.events;

public record DriverRatedEvent(Long driverId, Long rideId, Double rating, Long userId) {}
