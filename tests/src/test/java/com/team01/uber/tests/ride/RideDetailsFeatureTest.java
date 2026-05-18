package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F9 — GET /api/rides/{rideId}/details.
 *
 * <p>Returns a RideDetailsDTO with ride core + stop list + totalStops / completedStops counts.
 */
@DisplayName("S3-F9 — Ride details GET /api/rides/{rideId}/details")
class RideDetailsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC266 — Details returns ride + stops list with totalStops/completedStops")
    void tc266_detailsHasStopsAndCounts() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc266");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        // Add 2 stops
        Map<String, Object> stops = Map.of("stops", List.of(
                Map.of("stopOrder", 1, "latitude", 30.05, "longitude", 31.05,
                        "address", "A", "status", "PENDING", "metadata", Map.of()),
                Map.of("stopOrder", 2, "latitude", 30.06, "longitude", 31.06,
                        "address", "B", "status", "PENDING", "metadata", Map.of())));
        Http.Response addStops = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/stops")
                .bearer(rider.token()).json(stops).post();
        assertThat(addStops.status()).as("add stops for tc266").isBetween(200, 299);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/details")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("details happy path").isBetween(200, 299);
        assertThat(r.json().path("rideId").asLong()).isEqualTo(rideId);
        assertThat(r.json().path("stops").isArray()).as("stops field is array").isTrue();
        assertThat(r.json().path("stops").size()).isEqualTo(2);
        assertThat(r.json().path("totalStops").asInt()).isEqualTo(2);
        assertThat(r.json().has("completedStops"))
                .as("DTO must include completedStops")
                .isTrue();
    }

    @Test
    @DisplayName("TC267 — Details for unknown ride returns 404")
    void tc267_detailsUnknownRide_returns404() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc267");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/99999999/details")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("details unknown ride").isEqualTo(404);
    }

    @Test
    @DisplayName("TC348 — Details includes ride.metadata JSONB")
    void tc348_detailsIncludesMetadataJsonb() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc348");
        String tag = "premium_" + Nonce.nonce();
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 100.0, Map.of("feature", tag));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/details")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("details").isBetween(200, 299);
        assertThat(r.json().path("metadata").isObject())
                .as("metadata must be a JSON object")
                .isTrue();
        assertThat(r.json().path("metadata").path("feature").asText())
                .as("metadata.feature must round-trip")
                .isEqualTo(tag);
    }

    @Test
    @DisplayName("TC377 — Details for CANCELLED ride still returns 200 with status=CANCELLED")
    void tc377_detailsCancelledRide_returns200WithStatus() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc377");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Http.Response cancel = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/cancel")
                .bearer(rider.token()).put();
        assertThat(cancel.status()).as("cancel for tc377").isBetween(200, 299);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/details")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("details for cancelled ride").isBetween(200, 299);
        assertThat(r.json().path("status").asText())
                .as("details must surface the CANCELLED status")
                .isEqualTo("CANCELLED");
    }
}
