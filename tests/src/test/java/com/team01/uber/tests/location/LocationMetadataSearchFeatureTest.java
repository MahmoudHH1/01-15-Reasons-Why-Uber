package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F5 — Metadata JSONB search endpoint.
 */
@DisplayName("S4-F5 — Metadata search")
class LocationMetadataSearchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC283 — Metadata search ?operator=eq matches exact metadata value")
    void tc283_metadataSearch_eq_matchesExact() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc283");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc283");
        // Seed three locations with various speeds
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-04-15T08:00:00", 25.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-04-15T08:01:00", 75.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.2, 31.2,
                "2026-04-15T08:02:00", 75.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/metadata/search?key=speed&operator=eq&value=75")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("eq search 2xx").isBetween(200, 299);

        JsonNode arr = r.json();
        assertThat(arr.isArray()).as("result is array").isTrue();
        // Each returned row should have speed == 75
        for (JsonNode item : arr) {
            // Only check rows for our seeded driver to avoid cross-test interference
            if (item.path("driverId").asLong() != driverId) continue;
            double speed = item.path("metadata").path("speed").asDouble(-1);
            assertThat(speed)
                    .as("eq filter returns only matching values")
                    .isEqualTo(75.0);
        }
    }

    @Test
    @DisplayName("TC284 — Metadata search ?operator=gt&value=60 returns only rows with speed>60")
    void tc284_metadataSearch_gt_returnsAbove() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc284");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc284");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-04-15T08:00:00", 30.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-04-15T08:01:00", 80.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.2, 31.2,
                "2026-04-15T08:02:00", 90.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/metadata/search?key=speed&operator=gt&value=60")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("gt search 2xx").isBetween(200, 299);

        boolean has30 = false;
        boolean has80 = false;
        for (JsonNode item : r.json()) {
            if (item.path("driverId").asLong() != driverId) continue;
            double speed = item.path("metadata").path("speed").asDouble(-1);
            if (speed == 30.0) has30 = true;
            if (speed == 80.0) has80 = true;
            assertThat(speed).as("gt 60 excludes speed=" + speed).isGreaterThan(60.0);
        }
        assertThat(has30).as("speed=30 excluded by gt 60").isFalse();
        assertThat(has80).as("speed=80 included by gt 60").isTrue();
    }

    @Test
    @DisplayName("TC285 — Metadata search with operator=foo returns 400")
    void tc285_metadataSearch_invalidOperator_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc285");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/metadata/search?key=speed&operator=foo&value=10")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("invalid operator").isEqualTo(400);
    }

    @Test
    @DisplayName("TC374 — Metadata search ?operator=lt&value=40 returns rows below threshold")
    void tc374_metadataSearch_lt_returnsBelow() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc374");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc374");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-04-15T08:00:00", 20.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-04-15T08:01:00", 80.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/metadata/search?key=speed&operator=lt&value=40")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("lt search 2xx").isBetween(200, 299);

        boolean has20 = false;
        boolean has80 = false;
        for (JsonNode item : r.json()) {
            if (item.path("driverId").asLong() != driverId) continue;
            double speed = item.path("metadata").path("speed").asDouble(-1);
            if (speed == 20.0) has20 = true;
            if (speed == 80.0) has80 = true;
        }
        assertThat(has20).as("speed=20 included by lt 40").isTrue();
        assertThat(has80).as("speed=80 excluded by lt 40").isFalse();
    }
}
