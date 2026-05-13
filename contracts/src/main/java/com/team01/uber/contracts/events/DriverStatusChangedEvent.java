package com.team01.uber.contracts.events;

public record DriverStatusChangedEvent(Long driverId, String oldStatus, String newStatus) {}
