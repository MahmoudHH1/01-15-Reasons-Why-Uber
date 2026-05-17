package com.team01.uber.contracts.events;

import java.time.Instant;

/**
 * Published by any saga participant whose ride.completed / ride.cancelled consumer terminally fails
 * (business-rule veto, exhausted retries, optimistic-lock conflict, etc.). Consumed by ride-service's
 * SagaFeedbackConsumer, which triggers the existing ride.cancelled compensation cascade.
 *
 * @param rideId         saga correlation key
 * @param participant    "user" | "driver" | "location" | "payment"
 * @param originalEvent  "ride.completed" or "ride.cancelled"  (the latter is the anti-recursion case)
 * @param reason         short machine-readable code, e.g. "driver_lock_conflict", "user_not_active"
 * @param detail         human-readable, safe to log, no PII
 * @param correlationId  X-Correlation-ID propagated from the original event headers; nullable
 * @param occurredAt     publisher-side timestamp; idempotency tiebreaker
 */
public record SagaStepFailedEvent(
        Long rideId,
        String participant,
        String originalEvent,
        String reason,
        String detail,
        String correlationId,
        Instant occurredAt
) {}
