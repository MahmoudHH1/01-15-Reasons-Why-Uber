package com.team01.uber.contracts.events;

public record PaymentFailedEvent(Long paymentId, Long rideId, String reason) {}
