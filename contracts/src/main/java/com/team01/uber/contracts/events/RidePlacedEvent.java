package com.team01.uber.contracts.events;

public record RidePlacedEvent(Long rideId, Long userId, Long driverId) {}
