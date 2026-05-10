package com.team01.uber.contracts.events;

public record PaymentCompletedEvent(Long paymentId, Long rideId, Double amount) {}
