package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F1/S4-F2 CRUD + latest endpoints.
 */
@DisplayName("S4-F1/F2 — Location CRUD + latest")
class LocationCrudFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC275 — Latest returns the most-recent timestamp's location row")
    void tc275_latest_returnsMostRecent() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc275");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc275");

        long earlyId = LocationSeederSupport.seedLocation(
                me.token(), driverId, 30.0444, 31.2357,
                "2026-04-15T08:00:00", Map.of("speed", 40.0));
        long latestId = LocationSeederSupport.seedLocation(
                me.token(), driverId, 30.0555, 31.2400,
                "2026-04-15T18:00:00", Map.of("speed", 60.0));

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/" + driverId + "/latest")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("latest 2xx").isBetween(200, 299);
        long returnedId = r.json().path("id").asLong();
        assertThat(returnedId)
                .as("latest id should be the 18:00 record (" + latestId + ") not the 08:00 record (" + earlyId + ")")
                .isEqualTo(latestId);
    }

    @Test
    @DisplayName("TC276 — Latest for driver with zero locations returns 404")
    void tc276_latest_emptyDriver_returns404() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc276");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc276");

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/" + driverId + "/latest")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("latest with no locations").isEqualTo(404);
    }

    @Test
    @DisplayName("TC277 — POST location creates new Location row in PG")
    void tc277_postLocation_persistsRow() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc277");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc277");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.05);
        body.put("longitude", 31.24);
        body.put("metadata", Map.of("speed", 40.0, "heading", 90.0));

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/" + driverId)
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("POST /api/locations/driver/{id}").isBetween(200, 299);

        // Verify the location is queryable via latest now.
        Http.Response latest = Http.request(LOCATION_BASE, "/api/locations/driver/" + driverId + "/latest")
                .bearer(me.token())
                .get();
        assertThat(latest.status()).as("post-create latest returns 2xx").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC278 — Update with latitude=200 returns 400 (out of range)")
    void tc278_postLocation_invalidLatitude_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc278");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc278");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 200.0);
        body.put("longitude", 31.24);

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/" + driverId)
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("latitude=200 out of range").isEqualTo(400);
    }

    @Test
    @DisplayName("TC294 — Update for unknown driverId returns 404")
    void tc294_updateUnknownDriver_returns404() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc294");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.05);
        body.put("longitude", 31.24);

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/999999")
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("unknown driver POST location").isEqualTo(404);
    }

    @Test
    @DisplayName("TC296 — Latest for non-existent driver returns 404")
    void tc296_latest_unknownDriver_returns404() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc296");

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/999999/latest")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("unknown driver latest").isEqualTo(404);
    }

    @Test
    @DisplayName("TC352 — Update without latitude returns 400")
    void tc352_postLocation_missingLatitude_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc352");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc352");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("longitude", 31.23);

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/driver/" + driverId)
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("missing latitude").isEqualTo(400);
    }
}
