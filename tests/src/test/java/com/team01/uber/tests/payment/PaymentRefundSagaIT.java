package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Eventually;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAGA C — refund choreography.
 *
 * <p>Flow: POST /api/payments/{id}/refund-surge-adjusted
 *   → payment-service emits {@code payment.refunded} on {@code payment.events}
 *   → ride-service consumes via {@code ride.payment.refunded} queue
 *   → ride-service flips Ride.status to REFUNDED
 */
@Tag("saga")
@DisplayName("SAGA C — Refund cascade")
class PaymentRefundSagaIT extends BaseHttpTest {

    @Test
    @DisplayName("payment.refunded event triggers Ride.status=REFUNDED via RabbitMQ")
    void sagaC_refundCascadesToRide() {
        Seeders.Authed rider = Seeders.registerRider("sagaC");
        PaymentTestSupport.TypedDriver drv = PaymentTestSupport.seedTypedDriver(rider.token(), "sagaC", "SEDAN");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drv.driverId(), 120.0);
        long pid = PaymentTestSupport.processPaymentForRide(
                rider.token(), rider.uid(), rideId, 120.0, "CREDIT_CARD");

        // Wait for COMPLETED to settle before refunding
        Eventually.await(Duration.ofSeconds(8),
                () -> Mongo.count("payment_audit_trail",
                        Map.of("paymentId", pid, "action", "COMPLETED")) >= 1);

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "saga_test", "refundSurge", true))
                .post();
        assertThat(r.status()).as("refund 2xx").isBetween(200, 299);

        // (a) REFUNDED audit event
        Eventually.await(Duration.ofSeconds(15),
                "REFUNDED audit event for paymentId=" + pid,
                () -> Mongo.count("payment_audit_trail",
                        Map.of("paymentId", pid, "action", "REFUNDED")) >= 1);

        // (b) Payment.status = REFUNDED
        Http.Response paymentRead = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(paymentRead.json().path("status").asText()).isEqualTo("REFUNDED");

        // (c) Ride row eventually flips to REFUNDED via RabbitMQ saga consumer
        Eventually.await(Duration.ofSeconds(20),
                "Ride.status flipped to REFUNDED via saga consumer",
                () -> {
                    Http.Response read = Http.request(RIDE_BASE, "/api/rides/" + rideId).bearer(rider.token()).get();
                    if (read.status() < 200 || read.status() >= 300) return false;
                    return "REFUNDED".equals(read.json().path("status").asText());
                });
    }
}
