package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F7 — Purge old locations endpoint.
 */
@DisplayName("S4-F7 — Purge old locations")
class LocationPurgeFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC288 — DELETE purge?olderThanDays=30 deletes pre-cutoff rows")
    void tc288_purge_deletesPreCutoffRows() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc288");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc288");
        // Seed one old (2020) and one recent (now).
        long oldId = LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                "2020-01-01T08:00:00", 25.0);
        long recentId = LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.1, 31.1,
                LocationSeederSupport.nowTs(), 25.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/purge?olderThanDays=30")
                .bearer(me.token())
                .delete();
        assertThat(r.status()).as("purge 2xx").isBetween(200, 299);

        // Confirm old row is gone, recent row remains.
        Http.Response oldGet = Http.request(LOCATION_BASE, "/api/locations/" + oldId)
                .bearer(me.token())
                .get();
        assertThat(oldGet.status()).as("old (2020) row deleted").isEqualTo(404);

        Http.Response recentGet = Http.request(LOCATION_BASE, "/api/locations/" + recentId)
                .bearer(me.token())
                .get();
        assertThat(recentGet.status()).as("recent row preserved").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC289 — Purge with olderThanDays=-1 returns 400")
    void tc289_purge_negativeDays_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc289");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/purge?olderThanDays=-1")
                .bearer(me.token())
                .delete();
        assertThat(r.status()).as("negative olderThanDays").isEqualTo(400);
    }

    @Test
    @DisplayName("TC359 — Purge?olderThanDays=30 retains rows newer than 30 days")
    void tc359_purge_retainsRecentRows() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc359");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc359");
        long recentId = LocationSeederSupport.seedLocationWithSpeed(me.token(), driverId, 30.0, 31.0,
                LocationSeederSupport.nowTs(), 25.0);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/purge?olderThanDays=30")
                .bearer(me.token())
                .delete();
        assertThat(r.status()).as("purge 2xx").isBetween(200, 299);

        Http.Response get = Http.request(LOCATION_BASE, "/api/locations/" + recentId)
                .bearer(me.token())
                .get();
        assertThat(get.status())
                .as("recent (now) row should NOT be purged by olderThanDays=30")
                .isBetween(200, 299);
    }
}
