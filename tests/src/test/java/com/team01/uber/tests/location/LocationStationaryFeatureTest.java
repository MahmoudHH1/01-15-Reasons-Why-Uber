package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F9 — Stationary drivers endpoint.
 */
@DisplayName("S4-F9 — Stationary drivers")
class LocationStationaryFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC292 — Stationary returns drivers with last metadata.speed below maxSpeed")
    void tc292_stationary_returnsSlowDrivers() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc292");
        long slow = LocationSeederSupport.seedDriver(me.token(), "tc292slow");
        long fast = LocationSeederSupport.seedDriver(me.token(), "tc292fast");

        String now = LocalDateTime.now().withNano(0).toString();
        LocationSeederSupport.seedLocation(me.token(), slow, 30.0, 31.0, now, Map.of("speed", 2.0));
        LocationSeederSupport.seedLocation(me.token(), fast, 30.0, 31.0, now, Map.of("speed", 50.0));

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/stationary?maxSpeed=5&sinceMinutes=10")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("stationary 2xx").isBetween(200, 299);

        JsonNode arr = r.json();
        boolean hasSlow = false;
        boolean hasFast = false;
        for (JsonNode item : arr) {
            long id = item.path("driverId").asLong();
            if (id == slow) hasSlow = true;
            if (id == fast) hasFast = true;
        }
        assertThat(hasSlow).as("slow driver (speed=2) included").isTrue();
        assertThat(hasFast).as("fast driver (speed=50) excluded").isFalse();
    }

    @Test
    @DisplayName("TC293 — Stationary with sinceMinutes=-1 returns 400")
    void tc293_stationary_negativeSinceMinutes_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc293");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/stationary?maxSpeed=5&sinceMinutes=-1")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("negative sinceMinutes").isEqualTo(400);
    }

    @Test
    @DisplayName("TC358 — Stationary excludes drivers whose last update is older than sinceMinutes")
    void tc358_stationary_excludesStaleDrivers() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc358");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc358");
        // Seed location with timestamp 2 hours in the past
        String twoHoursAgo = LocalDateTime.now().minusHours(2).withNano(0).toString();
        LocationSeederSupport.seedLocation(me.token(), driverId, 30.0, 31.0, twoHoursAgo,
                Map.of("speed", 2.0));

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/stationary?maxSpeed=5&sinceMinutes=5")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("stationary 2xx").isBetween(200, 299);

        JsonNode arr = r.json();
        boolean hasStale = false;
        for (JsonNode item : arr) {
            if (item.path("driverId").asLong() == driverId) {
                hasStale = true;
                break;
            }
        }
        assertThat(hasStale).as("stale driver excluded").isFalse();
    }
}
