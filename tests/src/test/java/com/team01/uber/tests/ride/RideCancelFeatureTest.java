package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F7 — PUT /api/rides/{id}/cancel.
 *
 * <p>State machine: only REQUESTED or ACCEPTED rides can be cancelled.
 * On success: ride.status → CANCELLED, a ride.cancelled event is published.
 */
@DisplayName("S3-F7 — PUT /api/rides/{id}/cancel")
class RideCancelFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC262 — Cancel REQUESTED ride flips status to CANCELLED")
    void tc262_cancelRequested_flipsToCancelled() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc262");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/cancel")
                .bearer(rider.token())
                .put();

        assertThat(r.status()).as("cancel REQUESTED ride").isBetween(200, 299);
        assertThat(r.json().path("status").asText())
                .as("status after cancel must be CANCELLED")
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("TC263 — Cancel a COMPLETED ride returns 400")
    void tc263_cancelCompleted_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc263");
        long rideId = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/cancel")
                .bearer(rider.token())
                .put();

        assertThat(r.status())
                .as("cancel COMPLETED ride — state machine rejects, expected 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC270 — Cancel unknown ride returns 404")
    void tc270_cancelUnknownRide_returns404() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc270");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/99999999/cancel")
                .bearer(rider.token())
                .put();

        assertThat(r.status()).as("cancel unknown ride").isEqualTo(404);
    }

    @Test
    @DisplayName("TC346 — Cancel a CANCELLED ride returns 400")
    void tc346_cancelAlreadyCancelled_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc346");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        // First cancel → success
        Http.Response first = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/cancel")
                .bearer(rider.token())
                .put();
        assertThat(first.status()).as("first cancel").isBetween(200, 299);

        // Second cancel on already-CANCELLED must be rejected
        Http.Response second = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/cancel")
                .bearer(rider.token())
                .put();
        assertThat(second.status())
                .as("re-cancel CANCELLED ride must be 400")
                .isEqualTo(400);
    }
}
