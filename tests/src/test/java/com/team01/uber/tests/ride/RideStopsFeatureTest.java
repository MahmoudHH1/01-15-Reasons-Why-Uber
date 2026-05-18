package com.team01.uber.tests.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F8 — POST /api/rides/{rideId}/stops.
 *
 * <p>Stops are pre-trip detours added before the driver picks up. The M1 rule
 * is that only REQUESTED or ACCEPTED rides accept stops; the M3 carry-over
 * preserves this state-machine guard.
 */
@DisplayName("S3-F8 — Ride stops POST /api/rides/{rideId}/stops")
class RideStopsFeatureTest extends BaseHttpTest {

    private Map<String, Object> stopBody(int order, double lat, double lng, String address) {
        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("stopOrder", order);
        stop.put("latitude", lat);
        stop.put("longitude", lng);
        stop.put("address", address);
        stop.put("status", "PENDING");
        stop.put("metadata", Map.of());
        return stop;
    }

    @Test
    @DisplayName("TC264 — POST stops adds 2 RideStop rows for the ride")
    void tc264_postTwoStops_persistsBoth() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc264");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Map<String, Object> body = Map.of("stops", List.of(
                stopBody(1, 30.05, 31.05, "Stop A"),
                stopBody(2, 30.06, 31.06, "Stop B")));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/stops")
                .bearer(rider.token())
                .json(body)
                .post();

        assertThat(r.status()).as("post 2 stops").isBetween(200, 299);

        Http.Response list = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/stops")
                .bearer(rider.token())
                .get();
        assertThat(list.status()).as("list stops").isBetween(200, 299);
        assertThat(list.json().isArray()).as("stops list is array").isTrue();
        assertThat(list.json().size())
                .as("exactly 2 stops persisted for ride " + rideId)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("TC265 — Stops POST for unknown ride returns 404")
    void tc265_postStopsUnknownRide_returns404() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc265");

        Map<String, Object> body = Map.of("stops", List.of(
                stopBody(1, 30.05, 31.05, "Stop A")));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/99999999/stops")
                .bearer(rider.token())
                .json(body)
                .post();

        assertThat(r.status())
                .as("post stops to unknown ride — expected 404")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("TC347 — Stops POST with empty stops array returns 400")
    void tc347_emptyStopsArray_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc347");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Map<String, Object> body = Map.of("stops", List.of());

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/stops")
                .bearer(rider.token())
                .json(body)
                .post();

        assertThat(r.status())
                .as("empty stops array — expected 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC351 — Stops POST with duplicate stopOrder returns 400")
    void tc351_duplicateStopOrder_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc351");
        long rideId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Map<String, Object> body = Map.of("stops", List.of(
                stopBody(1, 30.05, 31.05, "Stop A"),
                stopBody(1, 30.07, 31.07, "Stop B-dup")));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/stops")
                .bearer(rider.token())
                .json(body)
                .post();

        assertThat(r.status())
                .as("duplicate stopOrder — expected 400")
                .isEqualTo(400);
    }
}
