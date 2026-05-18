package com.team01.uber.ride.messaging.consumers;

import com.team01.uber.contracts.events.SagaStepFailedEvent;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.messaging.publishers.RideEventPublisherService;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.service.RideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * Consumes NACKs from any saga participant whose ride.completed or ride.cancelled consumer
 * terminally failed. Triggers the existing ride.cancelled compensation cascade by transitioning
 * the ride to PAYMENT_FAILED and publishing ride.cancelled.
 *
 * Anti-recursion: if the NACK is for ride.cancelled itself (a failure of compensation), this
 * consumer logs ERROR but does NOT publish another ride.cancelled.
 */
@Component
public class SagaFeedbackConsumer {

    private static final Logger log = LoggerFactory.getLogger(SagaFeedbackConsumer.class);

    private static final EnumSet<RideStatus> COMPENSATABLE = EnumSet.of(
            RideStatus.COMPLETED,
            RideStatus.PAYMENT_PENDING
    );

    private final RideService rideService;
    private final RideEventPublisherService publisher;

    public SagaFeedbackConsumer(RideService rideService, RideEventPublisherService publisher) {
        this.rideService = rideService;
        this.publisher = publisher;
    }

    @RabbitListener(queues = "ride.saga-feedback")
    public void onSagaStepFailed(SagaStepFailedEvent event) {
        if (event.correlationId() != null) MDC.put("correlationId", event.correlationId());
        MDC.put("rideId", String.valueOf(event.rideId()));
        MDC.put("sagaParticipant", String.valueOf(event.participant()));
        try {
            // 1. Anti-recursion guard.
            if ("ride.cancelled".equals(event.originalEvent())) {
                log.error("Compensation failed at {} for ride {} reason={} detail={} - manual ops required",
                        event.participant(), event.rideId(), event.reason(), event.detail());
                return;
            }

            // 2. State-guard + idempotency: only compensate from a still-in-saga state.
            Ride compensated = rideService.transitionRideStatus(
                    event.rideId(), COMPENSATABLE, RideStatus.PAYMENT_FAILED);
            if (compensated == null) {
                log.info("saga-feedback ignored for ride {} (not in {}; participant={}, reason={})",
                        event.rideId(), COMPENSATABLE, event.participant(), event.reason());
                return;
            }

            // 3. Reuse the existing 5-hop compensation cascade.
            String reason = "saga_step_failed:" + event.participant() + ":" + event.reason();
            publisher.publishRideCancelled(compensated, reason);
            log.info("saga-feedback triggered compensation for ride {} due to {} ({})",
                    event.rideId(), event.participant(), event.reason());
        } finally {
            MDC.clear();
        }
    }
}
