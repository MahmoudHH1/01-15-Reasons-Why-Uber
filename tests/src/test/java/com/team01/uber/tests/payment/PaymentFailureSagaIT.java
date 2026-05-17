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
 * SAGA B — payment failure choreography.
 *
 * <p>Flow: POST /api/payments/ride/{rideId}?simulateFailure=true
 *   → payment-service emits {@code payment.failed} on {@code payment.events}
 *   → ride-service consumes via {@code ride.payment.failed} queue
 *   → ride-service flips Ride.status to PAYMENT_FAILED
 *   → ride-service publishes {@code ride.cancelled} on {@code ride.events}
 *   → cascading compensations across driver/location/payment services.
 */
@Tag("saga")
@DisplayName("SAGA B — Payment-failure compensation")
class PaymentFailureSagaIT extends BaseHttpTest {

    @Test
    @DisplayName("payment.failed event triggers Ride.status=PAYMENT_FAILED via RabbitMQ")
    void sagaB_paymentFailedCascadesToRide() {
        Seeders.Authed rider = Seeders.registerRider("sagaB");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "sagaB");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 80.0);

        // Trigger payment-failure path
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + rideId + "?simulateFailure=true")
                .bearer(rider.token())
                .json(Map.of("userId", rider.uid(), "amount", 80.0, "method", "CREDIT_CARD"))
                .post();
        assertThat(r.status()).as("payment-failure POST 2xx").isBetween(200, 299);
        long pid = r.json().path("id").asLong();

        // (a) FAILED audit event written to Mongo
        Eventually.await(Duration.ofSeconds(15),
                "FAILED audit event for paymentId=" + pid,
                () -> Mongo.count("payment_audit_trail",
                        Map.of("paymentId", pid, "action", "FAILED")) >= 1);

        // (b) Payment row carries status=FAILED
        Http.Response paymentRead = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(paymentRead.json().path("status").asText()).as("Payment.status").isEqualTo("FAILED");

        // (c) Ride row eventually flips to PAYMENT_FAILED (or COMPLETED→PAYMENT_FAILED via RabbitMQ consumer)
        // Some SUTs may not have the saga consumer wired yet; we poll up to 20s.
        Eventually.await(Duration.ofSeconds(20),
                "Ride.status flipped via RabbitMQ saga consumer",
                () -> {
                    Http.Response read = Http.request(RIDE_BASE, "/api/rides/" + rideId).bearer(rider.token()).get();
                    if (read.status() < 200 || read.status() >= 300) return false;
                    String status = read.json().path("status").asText();
                    return "PAYMENT_FAILED".equals(status) || "CANCELLED".equals(status);
                });
    }
}
