package com.team01.uber.contracts.events;

public record PaymentRefundedEvent(Long paymentId, Long rideId, Double refundAmount) {}
