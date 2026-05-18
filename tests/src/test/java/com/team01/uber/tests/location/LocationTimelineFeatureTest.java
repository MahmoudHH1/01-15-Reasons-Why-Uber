package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F12 — Location Tracking Timeline (Cassandra clustering DESC, cached 5m).
 */
@DisplayName("S4-F12 — Location tracking timeline")
class LocationTimelineFeatureTest extends BaseHttpTest {

    private static JsonNode unwrapContent(JsonNode node) {
        if (node.has("content") && node.path("content").isArray()) return node.path("content");
        return node;
    }

    @Test
    @DisplayName("TC128 — Timeline returns all events, most recent first")
    void tc128_timeline_returnsAllMostRecentFirst() throws InterruptedException {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc128");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc128");

        for (int i = 0; i < 3; i++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("latitude", 30.0 + i * 0.01);
            body.put("longitude", 31.0 + i * 0.01);
            body.put("speed", 40.0 + i);
            Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
            assertThat(post.status()).as("POST tracking #" + i + " 2xx").isBetween(200, 299);
            Thread.sleep(50);
        }

        Http.Response r = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(r.status()).as("timeline 2xx").isBetween(200, 299);
        JsonNode arr = unwrapContent(r.json());
        assertThat(arr.isArray()).as("timeline is array").isTrue();
        assertThat(arr.size()).as(">= 3 events").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("TC129 — Timeline with startTime/endTime returns only matching events")
    void tc129_timeline_withTimeRange_returnsMatchingEvents() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc129");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc129");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);
        body.put("speed", 40.0);
        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        // Use a wide window like the bash 40-location-service.sh — the SUT auto-stamps Cassandra
        // `timestamp` with server-now (§7.4) and accepts a LocalDateTime string.
        Http.Response r = LocationSeederSupport.getTimeline(me.token(), driverId,
                "?startTime=2020-01-01T00:00:00&endTime=2030-12-31T23:59:59");
        assertThat(r.status()).as("timeline 2xx").isBetween(200, 299);
        JsonNode arr = unwrapContent(r.json());
        assertThat(arr.size()).as(">= 1 event in wide window").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC130 — Timeline for non-existent driver returns 404")
    void tc130_timeline_unknownDriver_returns404() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc130");

        Http.Response r = LocationSeederSupport.getTimeline(me.token(), 999999L, null);
        assertThat(r.status()).as("unknown driver timeline").isEqualTo(404);
    }

    @Test
    @DisplayName("TC131 — Timeline without Authorization header returns 401")
    void tc131_timeline_noAuth_returns401() {
        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/1/tracking").get();
        assertThat(r.status()).as("no auth").isEqualTo(401);
    }

    @Test
    @DisplayName("TC132 — Timeline with malformed JWT returns 401")
    void tc132_timeline_malformedJwt_returns401() {
        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/1/tracking")
                .bearer(JwtTestHelper.malformedToken())
                .get();
        assertThat(r.status()).as("malformed JWT").isEqualTo(401);
    }

    @Test
    @DisplayName("TC133 — Timeline for driver with no events returns empty list")
    void tc133_timeline_emptyDriver_returnsEmpty() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc133");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc133");

        Http.Response r = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(r.status()).as("timeline 2xx").isBetween(200, 299);
        JsonNode arr = unwrapContent(r.json());
        assertThat(arr.isArray()).as("timeline is array").isTrue();
        assertThat(arr.size()).as("empty timeline for new driver").isZero();
    }

    @Test
    @DisplayName("TC134 — Two identical timeline requests return identical bodies (cached)")
    void tc134_timeline_twoIdenticalRequests_returnIdenticalBodies() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc134");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc134");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);
        body.put("speed", 40.0);
        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        Http.Response r1 = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(r1.status()).as("first GET 2xx").isBetween(200, 299);
        Http.Response r2 = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(r2.status()).as("second GET 2xx").isBetween(200, 299);

        assertThat(r2.body()).as("identical body across cached calls").isEqualTo(r1.body());
    }

    @Test
    @DisplayName("TC135 — Each event includes timestamp/latitude/longitude/speed")
    void tc135_timeline_dtoShape() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc135");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc135");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);
        body.put("speed", 45.0);
        body.put("heading", 180.0);
        body.put("accuracy", 5.0);
        body.put("notes", "TC135");
        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        Http.Response r = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(r.status()).as("timeline 2xx").isBetween(200, 299);
        JsonNode arr = unwrapContent(r.json());
        assertThat(arr.size()).as(">= 1 event").isGreaterThanOrEqualTo(1);
        JsonNode first = arr.get(0);
        boolean hasTimestamp = first.has("timestamp") || first.has("eventTime") || first.has("recordedAt");
        boolean hasLatitude = first.has("latitude") || first.has("lat");
        boolean hasLongitude = first.has("longitude") || first.has("lon") || first.has("lng");
        boolean hasSpeed = first.has("speed");
        assertThat(hasTimestamp).as("timestamp/eventTime/recordedAt").isTrue();
        assertThat(hasLatitude).as("latitude/lat").isTrue();
        assertThat(hasLongitude).as("longitude/lon/lng").isTrue();
        assertThat(hasSpeed).as("speed").isTrue();
    }
}
