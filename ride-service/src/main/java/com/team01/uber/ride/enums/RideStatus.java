package com.team01.uber.ride.enums;

public enum RideStatus {
    REQUESTED,
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,

    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    REFUNDED
}

// ── M3 saga statuses ──────────────────────────────────────────────────────
/** Set by ride-service when payment.initiated event is consumed from payment-service. */

/** Set by ride-service when payment.completed event is consumed from payment-service. */

/**
 * Set by ride-service when payment.failed event is consumed from payment-service.
 * Triggers the compensation cascade: ride-service publishes ride.cancelled so that
 * all saga participants (user, driver, location, payment) reverse their local state.
 */

/**
 * Set by ride-service when payment.refunded event is consumed from payment-service.
 * Terminal status of the compensation path — saga is fully resolved.
 */