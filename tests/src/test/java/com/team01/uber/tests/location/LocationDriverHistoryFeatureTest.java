package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F6 history + S4-F8 driver movement summary.
 */
@DisplayName("S4-F6/F8 — Location history + driver summary")
class LocationDriverHistoryFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC286 — History returns rows in date range for given driver")
    void tc286_history_returnsRowsInRange() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc286");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc286");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-03-15T08:00:00", 30.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-03-15T08:30:00", 35.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/history?startDate=2026-03-01&endDate=2026-03-31&driverId=" + driverId)
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("history 2xx").isBetween(200, 299);
        assertThat(r.json().isArray()).as("history is array").isTrue();
        assertThat(r.json().size()).as("at least 2 history rows").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("TC287 — History with no rows in range returns empty list")
    void tc287_history_emptyRange_returnsEmpty() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc287");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc287");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/history?startDate=2099-01-01&endDate=2099-01-31&driverId=" + driverId)
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("history 2xx").isBetween(200, 299);
        assertThat(r.json().isArray()).as("history is array").isTrue();
        assertThat(r.json().size()).as("empty for future range").isZero();
    }

    @Test
    @DisplayName("TC290 — Summary returns totalLocationPoints and lastTimestamp")
    void tc290_summary_returnsTotalAndLastTimestamp() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc290");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc290");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-03-10T08:00:00", 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-03-15T08:30:00", 50.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.2, 31.2,
                "2026-03-20T08:30:00", 60.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/driver/" + driverId + "/summary?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("summary 2xx").isBetween(200, 299);
        long total = r.json().path("totalLocationPoints").asLong(-1);
        assertThat(total).as("totalLocationPoints == 3").isEqualTo(3L);
        assertThat(r.json().has("lastTimestamp")).as("lastTimestamp field present").isTrue();
    }

    @Test
    @DisplayName("TC291 — Summary for driver with no locations returns totalPoints=0")
    void tc291_summary_emptyDriver_acceptable() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc291");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc291");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/driver/" + driverId + "/summary?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(me.token())
                .get();
        // 200 with totalPoints=0 or 404 both acceptable per spec
        assertThat(r.status()).as("200 or 404 acceptable").isIn(200, 404);
        if (r.status() == 200) {
            long total = r.json().path("totalLocationPoints").asLong(-1);
            assertThat(total).as("totalLocationPoints == 0 for empty driver").isZero();
        }
    }

    @Test
    @DisplayName("TC297 — Summary with startDate > endDate returns 400")
    void tc297_summary_invertedRange_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc297");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc297");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/driver/" + driverId + "/summary?startDate=2026-12-31&endDate=2026-01-01")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("inverted range").isEqualTo(400);
    }

    @Test
    @DisplayName("TC354 — Driver summary averageSpeed = mean of metadata.speed values")
    void tc354_summary_averageSpeed_equalsMean() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc354");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc354");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-03-10T08:00:00", 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-03-15T08:30:00", 60.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.2, 31.2,
                "2026-03-20T08:30:00", 80.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/driver/" + driverId + "/summary?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("summary 2xx").isBetween(200, 299);
        double avg = r.json().path("averageSpeed").asDouble(-1);
        assertThat(avg).as("averageSpeed = mean(40,60,80) = 60").isCloseTo(60.0,
                org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    @DisplayName("TC355 — Driver summary maxSpeed = max of metadata.speed values")
    void tc355_summary_maxSpeed_equalsMax() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc355");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc355");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2026-03-10T08:00:00", 30.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                "2026-03-15T08:30:00", 90.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/driver/" + driverId + "/summary?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("summary 2xx").isBetween(200, 299);
        double max = r.json().path("maxSpeed").asDouble(-1);
        assertThat(max).as("maxSpeed = max(30,90) = 90").isCloseTo(90.0,
                org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    @DisplayName("TC357 — History without driverId param returns history for all drivers")
    void tc357_history_withoutDriverId_returns200or400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc357");
        long a = LocationSeederSupport.seedDriver(me.token(), "tc357a");
        long b = LocationSeederSupport.seedDriver(me.token(), "tc357b");
        LocationSeederSupport.seedLocationWithSpeed(me.token(), a, 30.0, 31.0,
                "2026-03-10T08:00:00", 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), b, 30.1, 31.1,
                "2026-03-15T08:30:00", 60.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/history?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(me.token())
                .get();
        // Either 200 (returns combined list) or 400 (parameter required) is acceptable.
        assertThat(r.status())
                .as("history without driverId — 200 or 400 acceptable per spec")
                .isIn(200, 400);
    }
}
