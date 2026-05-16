package com.team01.uber.ride.messaging.consumers;

import com.team01.uber.contracts.events.PaymentCompletedEvent;
import com.team01.uber.contracts.events.PaymentFailedEvent;
import com.team01.uber.contracts.events.PaymentInitiatedEvent;
import com.team01.uber.contracts.events.PaymentRefundedEvent;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.messaging.publishers.RideEventPublisherService;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.service.RideService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private final RideService rideService;
    private final RideEventPublisherService publisherService;

    public PaymentEventConsumer(RideService rideService, RideEventPublisherService publisherService) {
        this.rideService = rideService;
        this.publisherService = publisherService;
    }

    @RabbitListener(queues = "ride.payment.initiated")
    public void onPaymentInitiated(PaymentInitiatedEvent event) {
        rideService.markRideStatus(event.rideId(), RideStatus.PAYMENT_PENDING);
    }

    @RabbitListener(queues = "ride.payment.completed")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        rideService.markRideStatus(event.rideId(), RideStatus.PAID);
    }

    /**
     * Compensation trigger: marks ride PAYMENT_FAILED then publishes ride.cancelled
     * so driver/user/location/payment-service reverse their committed state.
     * Publish happens after the @Transactional markRideStatus returns (commit-then-publish).
     */
    @RabbitListener(queues = "ride.payment.failed")
    public void onPaymentFailed(PaymentFailedEvent event) {
        Ride ride = rideService.markRideStatus(event.rideId(), RideStatus.PAYMENT_FAILED);
        if (ride != null) {
            publisherService.publishRideCancelled(ride, "payment_failed");
        }
    }

    @RabbitListener(queues = "ride.payment.refunded")
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        rideService.markRideStatus(event.rideId(), RideStatus.REFUNDED);
    }
}
