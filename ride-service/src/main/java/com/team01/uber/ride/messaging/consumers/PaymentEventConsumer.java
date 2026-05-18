package com.team01.uber.ride.messaging.consumers;

import com.team01.uber.contracts.events.PaymentCompletedEvent;
import com.team01.uber.contracts.events.PaymentFailedEvent;
import com.team01.uber.contracts.events.PaymentInitiatedEvent;
import com.team01.uber.contracts.events.PaymentRefundedEvent;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.messaging.publishers.RideEventPublisherService;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.service.RideService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final RideService rideService;
    private final RideEventPublisherService publisherService;

    public PaymentEventConsumer(RideService rideService, RideEventPublisherService publisherService) {
        this.rideService = rideService;
        this.publisherService = publisherService;
    }

    @RabbitListener(queues = "ride.payment.initiated")
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        Ride ride = rideService.transitionRideStatus(
                event.rideId(),
                EnumSet.of(RideStatus.COMPLETED),
                RideStatus.PAYMENT_PENDING);
        if (ride == null) {
            log.info("payment.initiated dropped for ride {} (out-of-order or duplicate)", event.rideId());
        }
    }

    @RabbitListener(queues = "ride.payment.completed")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        Ride ride = rideService.transitionRideStatus(
                event.rideId(),
                EnumSet.of(RideStatus.PAYMENT_PENDING),
                RideStatus.PAID);
        if (ride == null) {
            log.info("payment.completed dropped for ride {} (out-of-order or duplicate)", event.rideId());
        }
    }

    /**
     * Compensation trigger. Only emits ride.cancelled if the transition actually fired —
     * a late payment.failed arriving after PAID must NOT re-emit ride.cancelled.
     */
    @RabbitListener(queues = "ride.payment.failed")
    public void onPaymentFailed(PaymentFailedEvent event) {
        Ride ride = rideService.transitionRideStatus(
                event.rideId(),
                EnumSet.of(RideStatus.PAYMENT_PENDING),
                RideStatus.PAYMENT_FAILED);
        if (ride == null) {
            log.warn("payment.failed dropped for ride {} (not in PAYMENT_PENDING) - no compensation emitted",
                    event.rideId());
            return;
        }
        publisherService.publishRideCancelled(ride, "payment_failed");
    }

    @RabbitListener(queues = "ride.payment.refunded")
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        Ride ride = rideService.transitionRideStatus(
                event.rideId(),
                EnumSet.of(RideStatus.PAYMENT_FAILED),
                RideStatus.REFUNDED);
        if (ride == null) {
            log.info("payment.refunded dropped for ride {} (out-of-order or duplicate)", event.rideId());
        }
    }
}
