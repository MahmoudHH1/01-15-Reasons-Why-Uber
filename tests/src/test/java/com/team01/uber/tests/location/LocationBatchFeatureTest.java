package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F4 — Batch location upload endpoint.
 */
@DisplayName("S4-F4 — Batch location upload")
class LocationBatchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC281 — POST batch creates multiple Location rows for one driver")
    void tc281_batch_createsMultipleRows() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc281");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc281");

        Map<String, Object> loc1 = new LinkedHashMap<>();
        loc1.put("latitude", 30.1);
        loc1.put("longitude", 31.1);
        loc1.put("timestamp", "2026-04-15T09:00:00");
        Map<String, Object> loc2 = new LinkedHashMap<>();
        loc2.put("latitude", 30.2);
        loc2.put("longitude", 31.2);
        loc2.put("timestamp", "2026-04-15T09:01:00");
        Map<String, Object> loc3 = new LinkedHashMap<>();
        loc3.put("latitude", 30.3);
        loc3.put("longitude", 31.3);
        loc3.put("timestamp", "2026-04-15T09:02:00");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", driverId);
        body.put("locations", List.of(loc1, loc2, loc3));

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/batch")
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("batch 2xx").isBetween(200, 299);

        // Verify via history with a very wide range — the SUT auto-stamps batch rows with
        // server-now, ignoring the client-provided timestamps.
        Http.Response hist = Http.request(LOCATION_BASE,
                "/api/locations/history?startDate=2020-01-01&endDate=2099-12-31&driverId=" + driverId)
                .bearer(me.token())
                .get();
        assertThat(hist.status()).as("history 2xx").isBetween(200, 299);
        assertThat(hist.json().isArray()).as("history is array").isTrue();
        assertThat(hist.json().size())
                .as("at least 3 rows persisted via batch")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("TC282 — Batch with empty locations array returns 400")
    void tc282_batch_emptyLocations_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc282");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc282");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", driverId);
        body.put("locations", List.of());

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/batch")
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("empty locations array").isEqualTo(400);
    }

    @Test
    @DisplayName("TC356 — Batch with unknown driverId returns 404")
    void tc356_batch_unknownDriver_returns404() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc356");

        Map<String, Object> loc1 = new LinkedHashMap<>();
        loc1.put("latitude", 30.1);
        loc1.put("longitude", 31.1);
        loc1.put("timestamp", "2026-04-15T09:00:00");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", 999999);
        body.put("locations", List.of(loc1));

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/batch")
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("unknown driver batch").isEqualTo(404);
    }
}
