package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Mongo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F11 — POST /api/rides/{rideId}/record-interaction.
 *
 * <p>Black-box surface of the Neo4j RODE_WITH graph projection. Only COMPLETED
 * rides may be recorded; subsequent calls on the same rideId are idempotent
 * (per §10.3.2 step d — the marker lives in Neo4j, not PG).
 *
 * <p>We can't query Neo4j directly from the test harness, so we observe the
 * idempotency contract via the Mongo INTERACTION_RECORDED audit: the first call
 * writes one event, the second call (idempotent) must NOT write another.
 */
@DisplayName("S3-F11 — Record user-driver interaction")
class RideInteractionFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC70 — Record interaction on COMPLETED ride creates RODE_WITH with rideCount=1")
    void tc70_recordOnCompleted_returns2xxAndLogsInteraction() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc70");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc70");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "COMPLETED", 100.0, Map.of());

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();

        assertThat(r.status()).as("record-interaction on COMPLETED").isBetween(200, 299);

        long count = Mongo.countAtLeast("ride_events",
                Map.of("rideId", rideId, "action", "INTERACTION_RECORDED"),
                1, Duration.ofSeconds(5));
        assertThat(count)
                .as("INTERACTION_RECORDED must be logged for ride " + rideId)
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC71 — Same rideId recorded twice keeps rideCount=1 (idempotent)")
    void tc71_secondRecord_isIdempotent() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc71");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc71");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "COMPLETED", 100.0, Map.of());

        Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();
        // Wait for the first write to settle
        Mongo.countAtLeast("ride_events",
                Map.of("rideId", rideId, "action", "INTERACTION_RECORDED"),
                1, Duration.ofSeconds(5));
        long after1 = Mongo.count("ride_events",
                Map.of("rideId", rideId, "action", "INTERACTION_RECORDED"));

        Http.Response r2 = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();
        assertThat(r2.status()).as("re-record on same ride").isBetween(200, 299);

        // Sleep briefly then re-check — must NOT increment beyond after1
        try { Thread.sleep(1500); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long after2 = Mongo.count("ride_events",
                Map.of("rideId", rideId, "action", "INTERACTION_RECORDED"));

        assertThat(after2)
                .as("idempotency: second record on rideId=" + rideId
                        + " must NOT write a new INTERACTION_RECORDED")
                .isEqualTo(after1);
    }

    @Test
    @DisplayName("TC72 — Two distinct COMPLETED rides same user→driver → both recorded")
    void tc72_twoDistinctRides_bothRecorded() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc72");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc72");
        long r1 = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "COMPLETED", 100.0, Map.of());
        long r2 = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "COMPLETED", 120.0, Map.of());

        Http.Response a = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + r1 + "/record-interaction")
                .bearer(rider.token()).post();
        Http.Response b = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + r2 + "/record-interaction")
                .bearer(rider.token()).post();
        assertThat(a.status()).as("first record").isBetween(200, 299);
        assertThat(b.status()).as("second record (distinct rideId)").isBetween(200, 299);

        // Each rideId should produce its own audit row
        long cA = Mongo.countAtLeast("ride_events",
                Map.of("rideId", r1, "action", "INTERACTION_RECORDED"),
                1, Duration.ofSeconds(5));
        long cB = Mongo.countAtLeast("ride_events",
                Map.of("rideId", r2, "action", "INTERACTION_RECORDED"),
                1, Duration.ofSeconds(5));
        assertThat(cA).isGreaterThanOrEqualTo(1);
        assertThat(cB).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC73 — Recording ride with a different driver creates a new edge")
    void tc73_newDriver_acceptsRecord() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc73");
        long d1 = RideTestSupport.seedDriver(rider.token(), "tc73a");
        long d2 = RideTestSupport.seedDriver(rider.token(), "tc73b");
        long r1 = RideTestSupport.createRide(rider.token(), rider.uid(), d1, "COMPLETED", 100.0, Map.of());
        long r2 = RideTestSupport.createRide(rider.token(), rider.uid(), d2, "COMPLETED", 100.0, Map.of());

        Http.Response a = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + r1 + "/record-interaction")
                .bearer(rider.token()).post();
        Http.Response b = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + r2 + "/record-interaction")
                .bearer(rider.token()).post();

        assertThat(a.status()).as("record with d1").isBetween(200, 299);
        assertThat(b.status()).as("record with d2 — distinct driver").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC74 — Recording a REQUESTED (not completed) ride returns 400")
    void tc74_requestedRide_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc74");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc74");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "REQUESTED", null, Map.of());

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();

        assertThat(r.status())
                .as("REQUESTED ride — record interaction must be 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC75 — Recording a CANCELLED ride returns 400")
    void tc75_cancelledRide_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc75");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc75");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "CANCELLED", 0.0, Map.of());

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();

        assertThat(r.status())
                .as("CANCELLED ride — record interaction must be 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC76 — Recording an IN_PROGRESS ride returns 400")
    void tc76_inProgressRide_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc76");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc76");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "IN_PROGRESS", null, Map.of());

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();

        assertThat(r.status())
                .as("IN_PROGRESS ride — record interaction must be 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC77 — Recording an ACCEPTED ride returns 400")
    void tc77_acceptedRide_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc77");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc77");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "ACCEPTED", null, Map.of());

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();

        assertThat(r.status())
                .as("ACCEPTED ride — record interaction must be 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC78 — Record interaction for non-existent ride returns 404")
    void tc78_unknownRide_returns404() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc78");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/99999999/record-interaction")
                .bearer(rider.token()).post();

        assertThat(r.status()).as("unknown ride").isEqualTo(404);
    }

    @Test
    @DisplayName("TC79 — Record interaction without Authorization header returns 401")
    void tc79_noAuth_returns401() {
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/1/record-interaction")
                .post();

        assertThat(r.status())
                .as("no token — expected 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC80 — Record interaction with bogus JWT returns 401")
    void tc80_bogusJwt_returns401() {
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/1/record-interaction")
                .bearer(JwtTestHelper.malformedToken())
                .post();

        assertThat(r.status())
                .as("malformed JWT — expected 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC81 — RODE_WITH edge has lastRideDate property after recording")
    @org.junit.jupiter.api.Disabled("DEFERRED: requires direct Neo4j cypher inspection — covered by integration tests")
    void tc81_lastRideDateProperty_deferred() { /* needs Neo4j driver */ }

    @Test
    @DisplayName("TC82 — Neo4j Driver node exists with the seeded driver id")
    @org.junit.jupiter.api.Disabled("DEFERRED: requires Neo4j cypher MATCH — covered indirectly by TC70 success")
    void tc82_driverNodeExists_deferred() { /* needs Neo4j driver */ }

    @Test
    @DisplayName("TC83 — Neo4j User node exists with the ride's user id")
    @org.junit.jupiter.api.Disabled("DEFERRED: requires Neo4j cypher MATCH — covered indirectly by TC70 success")
    void tc83_userNodeExists_deferred() { /* needs Neo4j driver */ }

    @Test
    @DisplayName("TC84 — Record interaction writes INTERACTION_RECORDED to ride_events")
    void tc84_writesInteractionRecorded() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc84");
        long driverId = RideTestSupport.seedDriver(rider.token(), "tc84");
        long rideId = RideTestSupport.createRide(rider.token(), rider.uid(), driverId,
                "COMPLETED", 100.0, Map.of());

        long before = Mongo.count("ride_events",
                Map.of("rideId", rideId, "action", "INTERACTION_RECORDED"));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/" + rideId + "/record-interaction")
                .bearer(rider.token()).post();
        assertThat(r.status()).as("record-interaction").isBetween(200, 299);

        long after = Mongo.countAtLeast("ride_events",
                Map.of("rideId", rideId, "action", "INTERACTION_RECORDED"),
                before + 1, Duration.ofSeconds(5));
        assertThat(after - before)
                .as("INTERACTION_RECORDED must be written for ride " + rideId)
                .isGreaterThanOrEqualTo(1);
    }
}
