package com.team01.uber.tests.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Eventually;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Rabbit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saga A — Ride completion happy path.
 *
 * <p>Per uber-m3.md §8.3, the choreography chain is:
 * <ol>
 *   <li>Rider places a ride → ride-service: ride.placed</li>
 *   <li>Driver-service consumes ride.placed → driver BUSY</li>
 *   <li>Ride completes (pre-saga Feign checks pass) → ride-service: ride.completed</li>
 *   <li>Payment-service consumes ride.completed → PENDING payment created → payment.initiated</li>
 *   <li>Driver-service consumes ride.completed → driver AVAILABLE, totals updated</li>
 *   <li>Ride-service consumes payment.initiated → ride.status flips PAYMENT_PENDING</li>
 *   <li>(In the full flow, payment.completed → ride.status flips PAID)</li>
 * </ol>
 *
 * <p>This IT verifies the round-trip from POST /api/rides through the
 * choreography back to the ride row settling at PAYMENT_PENDING or PAID
 * within the await timeout. Both terminal states are acceptable because
 * payment.completed publication depends on the payment processor mock —
 * the contract this IT defends is that the ride did NOT stall in COMPLETED.
 *
 * <p>Tagged {@code saga} so CI can opt it out when running quick smoke loops.
 */
@DisplayName("Saga A — ride.completed → payment chain → ride.status terminal")
@Tag("saga")
class RideCompletionSagaIT extends BaseHttpTest {

    private Map<String, Object> rideBodyWithStatus(long userId, long driverId, String status, Double fare) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("driverId", driverId);
        body.put("pickupLatitude", 30.0);
        body.put("pickupLongitude", 31.0);
        body.put("dropoffLatitude", 30.1);
        body.put("dropoffLongitude", 31.1);
        body.put("status", status);
        if (fare != null) body.put("fare", fare);
        body.put("requestedAt", java.time.LocalDateTime.now().toString());
        body.put("completedAt", java.time.LocalDateTime.now().toString());
        body.put("metadata", Map.of());
        return body;
    }

    @Test
    @DisplayName("Saga A (hop 1/3) — ride.completed is published when /complete transitions IN_PROGRESS → COMPLETED")
    void sagaA_hop1_rideCompletedIsPublished() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("sagaA1");
        long driverId = RideTestSupport.seedDriverWithStatus(rider.token(), "sagaA1", "AVAILABLE");
        seedLocationPing(rider.token(), driverId);

        long ridePublishedBefore = safePublished("ride.events");

        long rideId = driveFullLifecycle(rider, driverId);
        assertThat(rideId)
                .as("Saga A.1 precondition — driveFullLifecycle must reach COMPLETED")
                .isPositive();

        // ride.events publish counter must have advanced (the COMPLETED
        // transition fires ride.completed at the exchange).
        Eventually.await(Duration.ofSeconds(15),
                "ride.events publish counter must advance after completion",
                () -> safePublished("ride.events") > ridePublishedBefore);
    }

    @Test
    @DisplayName("Saga A (hop 2/3) — payment-service consumes ride.completed and creates a PENDING payment")
    void sagaA_hop2_paymentCreatedAfterRideCompleted() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("sagaA2");
        long driverId = RideTestSupport.seedDriverWithStatus(rider.token(), "sagaA2", "AVAILABLE");
        seedLocationPing(rider.token(), driverId);

        long rideId = driveFullLifecycle(rider, driverId);
        assertThat(rideId).as("Saga A.2 precondition").isPositive();
        final long observedRideId = rideId;

        // Search payments for a PENDING/COMPLETED record on this rideId.
        // The payment.method field on Payment is @NotNull; payment-service's
        // processRideCompleted() must set a default method (e.g. CREDIT_CARD)
        // when consuming ride.completed for this assertion to succeed.
        // SUT NOTE (regression): payment-service/src/main/java/.../PaymentService.java:612
        //   processRideCompleted does not call payment.setMethod(...) → @NotNull
        //   violation, message is NACKed three times, lands on DLQ, payment never
        //   created. This test stays failing until the SUT sets a default method.
        Eventually.await(Duration.ofSeconds(15),
                "payment record must exist for ride " + observedRideId
                        + " (payment-service must consume ride.completed and create a PENDING row)",
                () -> {
                    Http.Response r = Http.request(
                            System.getProperty("service.payment.base", "http://localhost:8085"),
                            "/api/payments/search?status=PENDING")
                            .bearer(rider.token()).get();
                    if (r.status() < 200 || r.status() >= 300) return false;
                    for (JsonNode p : r.json()) {
                        if (p.path("rideId").asLong() == observedRideId) return true;
                    }
                    return false;
                });
    }

    @Test
    @DisplayName("Saga A (hop 3/3) — ride.status settles to PAYMENT_PENDING / PAID after consumer chain")
    void sagaA_hop3_rideStatusSettlesAfterChain() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("sagaA3");
        long driverId = RideTestSupport.seedDriverWithStatus(rider.token(), "sagaA3", "AVAILABLE");
        seedLocationPing(rider.token(), driverId);

        long rideId = driveFullLifecycle(rider, driverId);
        assertThat(rideId).as("Saga A.3 precondition").isPositive();
        final long observedRideId = rideId;

        // Ride row must leave COMPLETED and settle into a payment-state.
        // Acceptable terminal states: PAYMENT_PENDING / PAID / PAYMENT_FAILED.
        // A stuck COMPLETED indicates the consumer chain is broken — same
        // root cause as hop 2.
        Eventually.await(Duration.ofSeconds(15),
                "ride " + observedRideId + " must move from COMPLETED to a payment-state",
                () -> {
                    JsonNode node = Http.request(RideTestSupport.RIDE_BASE,
                            "/api/rides/" + observedRideId)
                            .bearer(rider.token()).get().json();
                    String status = node.path("status").asText("");
                    return "PAYMENT_PENDING".equals(status)
                            || "PAID".equals(status)
                            || "PAYMENT_FAILED".equals(status);
                });
    }

    /** POST one location ping for the driver so location-service has a recent point. */
    private void seedLocationPing(String token, long driverId) {
        Map<String, Object> ping = new LinkedHashMap<>();
        ping.put("latitude", 30.05);
        ping.put("longitude", 31.05);
        ping.put("metadata", Map.of());
        Http.Response r = Http.request(
                System.getProperty("service.location.base", "http://localhost:8084"),
                "/api/locations/driver/" + driverId)
                .bearer(token).json(ping).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("Could not seed driver location ping: "
                    + r.status() + " " + r.body());
        }
    }

    /**
     * Drive REQUESTED → assign → IN_PROGRESS → complete. Returns the rideId
     * on success, -1 on a pre-saga Feign refusal.
     */
    private long driveFullLifecycle(RideTestSupport.AuthedRider rider, long driverId) {
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), driverId);

        Http.Response assign = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/assign?driverId=" + driverId)
                .bearer(rider.token()).put();
        if (assign.status() < 200 || assign.status() >= 300) return -1;

        // Wait for the driver to settle into BUSY. The assign endpoint emits
        // ride.placed which driver-service consumes ASYNCHRONOUSLY to flip
        // BUSY. If we /complete before that consumer commits, the pre-saga
        // Feign chain (BUSY check) refuses with 400.
        Eventually.await(Duration.ofSeconds(10),
                "driver " + driverId + " must become BUSY before /complete",
                () -> {
                    Http.Response av = Http.request(
                            System.getProperty("service.driver.base", "http://localhost:8082"),
                            "/api/drivers/" + driverId + "/availability")
                            .bearer(rider.token()).get();
                    return av.status() >= 200 && av.status() < 300
                            && "BUSY".equals(av.json().path("status").asText());
                });

        // Coerce to IN_PROGRESS via the unchecked update endpoint.
        JsonNode current = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + rideId)
                .bearer(rider.token()).get().json();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("userId", current.path("userId").asLong());
        update.put("driverId", current.path("driverId").asLong());
        update.put("pickupLatitude", current.path("pickupLatitude").asDouble());
        update.put("pickupLongitude", current.path("pickupLongitude").asDouble());
        update.put("dropoffLatitude", current.path("dropoffLatitude").asDouble());
        update.put("dropoffLongitude", current.path("dropoffLongitude").asDouble());
        update.put("status", "IN_PROGRESS");
        update.put("fare", 50.0);
        update.put("metadata", Map.of());
        update.put("requestedAt", current.path("requestedAt").asText());
        if (current.hasNonNull("completedAt")) {
            update.put("completedAt", current.path("completedAt").asText());
        }
        Http.Response coerce = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + rideId)
                .bearer(rider.token()).json(update).put();
        if (coerce.status() < 200 || coerce.status() >= 300) return -1;

        Http.Response complete = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/complete")
                .bearer(rider.token()).put();
        if (complete.status() < 200 || complete.status() >= 300) return -1;
        return rideId;
    }

    private long safePublished(String exchange) {
        try { return Rabbit.publishedTotal(exchange); }
        catch (Exception e) { return 0L; }
    }
}
