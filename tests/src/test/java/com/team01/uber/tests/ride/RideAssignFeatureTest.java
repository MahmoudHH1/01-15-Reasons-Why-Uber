package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F2 — PUT /api/rides/{id}/assign?driverId={id}.
 *
 * <p>M3 invocation flow: ride-service hits driver-service via Feign to verify
 * the candidate driver is AVAILABLE before accepting the assignment. The
 * post-condition is that the local ride row flips to ACCEPTED and a
 * ride.placed event is published (validated indirectly via state — see the
 * dedicated saga IT for the cross-service consumption).
 */
@DisplayName("S3-F2 — PUT /api/rides/{id}/assign")
class RideAssignFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC251 — PUT assign sets driver FK and flips status to ACCEPTED")
    void tc251_assignAvailableDriver_setsStatusAccepted() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc251");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc251");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/assign?driverId=" + driverId)
                .bearer(rider.token())
                .put();

        assertThat(r.status()).as("assign happy path").isBetween(200, 299);
        assertThat(r.json().path("status").asText())
                .as("status after assign must be ACCEPTED")
                .isEqualTo("ACCEPTED");
        assertThat(r.json().path("driverId").asLong())
                .as("driverId FK must be set to the assigned driver")
                .isEqualTo(driverId);
    }

    @Test
    @DisplayName("TC252 — Assigning a ride already in ACCEPTED state returns 400")
    void tc252_reassignAcceptedRide_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc252");
        long d1 = RideTestSupport.seedDriver(rider.token(), "tc252a");
        long d2 = RideTestSupport.seedDriver(rider.token(), "tc252b");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        // First assign (success → ACCEPTED)
        Http.Response first = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/assign?driverId=" + d1)
                .bearer(rider.token())
                .put();
        assertThat(first.status()).as("first assign").isBetween(200, 299);

        // Second assign on already-accepted ride must be rejected
        Http.Response second = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/assign?driverId=" + d2)
                .bearer(rider.token())
                .put();
        assertThat(second.status())
                .as("re-assigning already-ACCEPTED ride must be 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC253 — Assign unknown rideId returns 404")
    void tc253_assignUnknownRide_returns404() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc253");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc253");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/99999999/assign?driverId=" + driverId)
                .bearer(rider.token())
                .put();

        assertThat(r.status()).as("assign unknown ride").isEqualTo(404);
    }

    @Test
    @DisplayName("TC272 — Assign without driverId param returns 400")
    void tc272_assignWithoutDriverId_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc272");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/assign")
                .bearer(rider.token())
                .put();

        assertThat(r.status())
                .as("missing required driverId param must be 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC349 — Assign OFFLINE driver returns 400")
    void tc349_assignOfflineDriver_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc349");
        long offlineDriverId = RideTestSupport.seedDriverWithStatus(rider.token(), "tc349", "OFFLINE");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/assign?driverId=" + offlineDriverId)
                .bearer(rider.token())
                .put();

        assertThat(r.status())
                .as("assigning OFFLINE driver — must be rejected with 400")
                .isEqualTo(400);
    }
}
