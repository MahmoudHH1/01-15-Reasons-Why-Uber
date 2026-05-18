package com.team01.uber.ride.messaging.consumers;

import com.team01.uber.contracts.events.PaymentCompletedEvent;
import com.team01.uber.contracts.events.PaymentFailedEvent;
import com.team01.uber.contracts.events.PaymentInitiatedEvent;
import com.team01.uber.contracts.events.PaymentRefundedEvent;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.messaging.publishers.RideEventPublisherService;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.service.RideService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus / §8.6 — Saga E2E (ride-service participant).
 *
 * Verifies the ride-service consumer side of the choreography:
 *   - payment.initiated  -> ride.status = PAYMENT_PENDING  (§8.2 step 4)
 *   - payment.completed  -> ride.status = PAID             (§8.2 step 6a)
 *   - payment.failed     -> ride.status = PAYMENT_FAILED   (§8.2 step 6b)
 *                           AND ride.cancelled published with reason="payment_failed"
 *                           (§8.4 compensation cascade — Scenario B in §8.6)
 *   - payment.refunded   -> ride.status = REFUNDED         (§8.2 step 7)
 *
 * Compensation flow is the key bonus item: per §15.3 "inject payment failure,
 * assert compensation runs." That is exactly what the payment.failed test
 * verifies — ride is flipped to PAYMENT_FAILED AND ride.cancelled is published.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentEventConsumer — ride-service saga participant (§8.2 / §8.6)")
class PaymentEventConsumerSagaTest {

    @Mock private RideService rideService;
    @Mock private RideEventPublisherService publisherService;

    @InjectMocks
    private PaymentEventConsumer consumer;

    @Test
    @DisplayName("§8.2 step 4: payment.initiated -> ride.status = PAYMENT_PENDING; no ride.cancelled")
    void onPaymentInitiated_marksPaymentPending() {
        PaymentInitiatedEvent event = new PaymentInitiatedEvent(1L, 10L, 200.0);

        consumer.onPaymentInitiated(event);

        verify(rideService).markRideStatus(10L, RideStatus.PAYMENT_PENDING);
        verify(publisherService, never()).publishRideCancelled(any(Ride.class), anyString());
    }

    @Test
    @DisplayName("§8.2 step 6a (happy path): payment.completed -> ride.status = PAID")
    void onPaymentCompleted_marksPaid() {
        PaymentCompletedEvent event = new PaymentCompletedEvent(1L, 10L, 200.0);

        consumer.onPaymentCompleted(event);

        verify(rideService).markRideStatus(10L, RideStatus.PAID);
        verify(publisherService, never()).publishRideCancelled(any(Ride.class), anyString());
    }

    @Test
    @DisplayName("§8.2 step 6b + §8.4 compensation: payment.failed -> ride.status = PAYMENT_FAILED AND ride.cancelled(reason=payment_failed) published; commit-then-publish order")
    void onPaymentFailed_compensationCascadeFires() {
        Ride paymentFailedRide = new Ride();
        paymentFailedRide.setId(10L);
        paymentFailedRide.setUserId(1L);
        paymentFailedRide.setDriverId(5L);
        paymentFailedRide.setStatus(RideStatus.PAYMENT_FAILED);
        when(rideService.markRideStatus(10L, RideStatus.PAYMENT_FAILED)).thenReturn(paymentFailedRide);

        consumer.onPaymentFailed(new PaymentFailedEvent(1L, 10L, "card declined"));

        InOrder order = inOrder(rideService, publisherService);
        order.verify(rideService).markRideStatus(10L, RideStatus.PAYMENT_FAILED);
        order.verify(publisherService).publishRideCancelled(eq(paymentFailedRide), eq("payment_failed"));
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("§8.6 saga idempotency: re-delivered payment.failed -> markRideStatus called twice, but ride.cancelled published only once")
    void onPaymentFailed_redeliveredEvent_compensationFiresOnlyOnce() {
        Ride paymentFailedRide = new Ride();
        paymentFailedRide.setId(10L);
        paymentFailedRide.setStatus(RideStatus.PAYMENT_FAILED);
        when(rideService.markRideStatus(10L, RideStatus.PAYMENT_FAILED))
                .thenReturn(paymentFailedRide)
                .thenReturn(null);

        PaymentFailedEvent event = new PaymentFailedEvent(1L, 10L, "card declined");
        consumer.onPaymentFailed(event);
        consumer.onPaymentFailed(event);

        verify(rideService, times(2)).markRideStatus(10L, RideStatus.PAYMENT_FAILED);
        verify(publisherService, times(1)).publishRideCancelled(eq(paymentFailedRide), eq("payment_failed"));
    }

    @Test
    @DisplayName("§8.6 idempotency: payment.failed when ride is already in terminal state -> no compensation re-fire")
    void onPaymentFailed_rideMissing_noCompensation() {
        // markRideStatus returns null when the ride is in a terminal state and
        // RideService refuses the transition — see §16 rule 11 (state-based idempotency).
        when(rideService.markRideStatus(10L, RideStatus.PAYMENT_FAILED)).thenReturn(null);

        consumer.onPaymentFailed(new PaymentFailedEvent(1L, 10L, "card declined"));

        verify(rideService).markRideStatus(10L, RideStatus.PAYMENT_FAILED);
        verify(publisherService, never()).publishRideCancelled(any(Ride.class), anyString());
    }

    @Test
    @DisplayName("§8.2 step 7: payment.refunded -> ride.status = REFUNDED")
    void onPaymentRefunded_marksRefunded() {
        consumer.onPaymentRefunded(new PaymentRefundedEvent(1L, 10L, 200.0));

        verify(rideService).markRideStatus(10L, RideStatus.REFUNDED);
        verify(publisherService, never()).publishRideCancelled(any(Ride.class), anyString());
    }
}
