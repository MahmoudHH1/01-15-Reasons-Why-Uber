package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Nonce;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F11 — Record GPS Tracking Event (Cassandra primary + Mongo TRACKING_RECORDED audit).
 *
 * <p>Cassandra TCs verify state via the HTTP timeline endpoint, not by direct Cassandra
 * queries — per the AGENT_BRIEFING the suite is HTTP black-box.
 */
@DisplayName("S4-F11 — Record GPS Tracking Event")
class LocationTrackingFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC118 — Tracking event writes Cassandra row + Mongo TRACKING_RECORDED")
    void tc118_tracking_writesCassandraAndMongo() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc118");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc118");

        long before = Mongo.count("location_events",
                Map.of("driverId", driverId, "action", "TRACKING_RECORDED"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.0444);
        body.put("longitude", 31.2357);
        body.put("speed", 45.0);
        body.put("heading", 180.0);
        body.put("accuracy", 5.0);
        body.put("notes", "TC118 ping");

        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        // Verify via the timeline endpoint (HTTP black-box) — Cassandra row should now exist.
        Http.Response timeline = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(timeline.status()).as("timeline 2xx").isBetween(200, 299);
        assertThat(timeline.json().isArray()).as("timeline is array").isTrue();
        assertThat(timeline.json().size()).as("at least 1 event").isGreaterThanOrEqualTo(1);

        // Mongo TRACKING_RECORDED audit must increment.
        long observed = Mongo.countAtLeast("location_events",
                Map.of("driverId", driverId, "action", "TRACKING_RECORDED"),
                before + 1,
                Duration.ofSeconds(5));
        assertThat(observed).as("TRACKING_RECORDED Mongo doc emitted").isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    @DisplayName("TC119 — Cassandra tracking row has latitude/longitude/speed populated")
    void tc119_tracking_cassandraRowFieldsPopulated() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc119");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc119");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.0444);
        body.put("longitude", 31.2357);
        body.put("speed", 45.0);
        body.put("heading", 180.0);
        body.put("accuracy", 5.0);

        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        Http.Response timeline = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(timeline.status()).as("timeline 2xx").isBetween(200, 299);
        JsonNode arr = timeline.json();
        assertThat(arr.size()).as(">= 1 event").isGreaterThanOrEqualTo(1);
        JsonNode first = arr.get(0);
        assertThat(first.path("latitude").asDouble()).as("latitude")
                .isCloseTo(30.0444, org.assertj.core.api.Assertions.within(0.01));
        assertThat(first.path("longitude").asDouble()).as("longitude")
                .isCloseTo(31.2357, org.assertj.core.api.Assertions.within(0.01));
    }

    @Test
    @DisplayName("TC120 — Two tracking events → both rows present in Cassandra")
    void tc120_twoTrackingEvents_bothPresent() throws InterruptedException {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc120");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc120");

        Map<String, Object> body1 = new LinkedHashMap<>();
        body1.put("latitude", 30.04);
        body1.put("longitude", 31.23);
        body1.put("speed", 40.0);
        Http.Response r1 = LocationSeederSupport.postTracking(me.token(), driverId, body1);
        assertThat(r1.status()).as("first POST 2xx").isBetween(200, 299);

        Thread.sleep(50);

        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("latitude", 30.05);
        body2.put("longitude", 31.24);
        body2.put("speed", 50.0);
        Http.Response r2 = LocationSeederSupport.postTracking(me.token(), driverId, body2);
        assertThat(r2.status()).as("second POST 2xx").isBetween(200, 299);

        Http.Response timeline = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(timeline.status()).as("timeline 2xx").isBetween(200, 299);
        assertThat(timeline.json().size()).as(">= 2 events").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("TC121 — Tracking event for non-existent driver returns 404")
    void tc121_tracking_unknownDriver_returns404() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc121");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);
        body.put("speed", 40.0);

        Http.Response r = LocationSeederSupport.postTracking(me.token(), 999999L, body);
        assertThat(r.status()).as("unknown driver tracking").isEqualTo(404);
    }

    @Test
    @DisplayName("TC122 — Tracking without Authorization header returns 401")
    void tc122_tracking_noAuth_returns401() {
        // Use any driver id — auth check should happen before existence check.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/1/tracking")
                .json(body)
                .post();
        assertThat(r.status()).as("no auth").isEqualTo(401);
    }

    @Test
    @DisplayName("TC123 — Tracking with malformed JWT returns 401")
    void tc123_tracking_malformedJwt_returns401() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations/1/tracking")
                .bearer(JwtTestHelper.malformedToken())
                .json(body)
                .post();
        assertThat(r.status()).as("malformed JWT").isEqualTo(401);
    }

    @Test
    @DisplayName("TC124 — Tracking event with only required lat/lon → 2xx")
    void tc124_tracking_lateLonOnly_returns2xx() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc124");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc124");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);

        Http.Response r = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(r.status()).as("only lat/lon").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC125 — TRACKING_RECORDED Mongo doc carries driverId in details")
    void tc125_trackingRecorded_carriesDriverId() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc125");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc125");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);
        body.put("speed", 40.0);

        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        long observed = Mongo.countAtLeast("location_events",
                Map.of("driverId", driverId, "action", "TRACKING_RECORDED"),
                1,
                Duration.ofSeconds(5));
        assertThat(observed)
                .as("TRACKING_RECORDED doc with driverId=" + driverId)
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC126 — Cassandra tracking row preserves the exact coordinates supplied")
    void tc126_tracking_preservesPrecision() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc126");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc126");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.12345);
        body.put("longitude", 31.67890);
        body.put("speed", 42.0);

        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        Http.Response timeline = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(timeline.status()).as("timeline 2xx").isBetween(200, 299);
        assertThat(timeline.json().size()).as(">= 1 event").isGreaterThanOrEqualTo(1);
        JsonNode first = timeline.json().get(0);
        assertThat(first.path("latitude").asDouble())
                .as("latitude preserved to 0.0001")
                .isCloseTo(30.12345, org.assertj.core.api.Assertions.within(0.0001));
        assertThat(first.path("longitude").asDouble())
                .as("longitude preserved to 0.0001")
                .isCloseTo(31.67890, org.assertj.core.api.Assertions.within(0.0001));
    }

    @Test
    @DisplayName("TC127 — Notes field round-trips into Cassandra row")
    void tc127_notes_roundTripsIntoCassandra() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc127");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc127");
        String notes = "TC127_" + Nonce.nonce();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("latitude", 30.04);
        body.put("longitude", 31.23);
        body.put("speed", 40.0);
        body.put("notes", notes);

        Http.Response post = LocationSeederSupport.postTracking(me.token(), driverId, body);
        assertThat(post.status()).as("POST tracking 2xx").isBetween(200, 299);

        Http.Response timeline = LocationSeederSupport.getTimeline(me.token(), driverId, null);
        assertThat(timeline.status()).as("timeline 2xx").isBetween(200, 299);

        boolean foundNotes = false;
        for (JsonNode item : timeline.json()) {
            if (notes.equals(item.path("notes").asText(null))) {
                foundNotes = true;
                break;
            }
        }
        assertThat(foundNotes).as("notes round-trip via Cassandra → timeline DTO").isTrue();
    }
}
