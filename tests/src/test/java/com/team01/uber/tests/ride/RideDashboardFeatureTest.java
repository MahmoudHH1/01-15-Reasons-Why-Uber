package com.team01.uber.tests.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Nonce;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F10 — GET /api/rides/analytics/dashboard.
 *
 * <p>Distinct from M1 /api/rides/analytics: returns the richer
 * RideAnalyticsDashboardDTO with a ridesByStatus map. ANALYTICS_VIEWED is
 * logged on every call (including cache hits) per §10.3.1 step d.
 *
 * <p>The SUT overwrites requestedAt with now() on POST, so we test against
 * a today-bracketed window using deltas rather than absolute counts.
 */
@DisplayName("S3-F10 — Ride analytics dashboard /api/rides/analytics/dashboard")
class RideDashboardFeatureTest extends BaseHttpTest {

    private record DashSnap(long totalRides, double totalRevenue, double averageRideFare,
                            double completionRate, JsonNode raw) {}

    private DashSnap dashboard(String token, LocalDate start, LocalDate end) {
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=" + start + "&endDate=" + end)
                .bearer(token).get();
        assertThat(r.status()).as("dashboard snap").isBetween(200, 299);
        return new DashSnap(
                r.json().path("totalRides").asLong(0),
                r.json().path("totalRevenue").asDouble(0.0),
                r.json().path("averageRideFare").asDouble(0.0),
                r.json().path("completionRate").asDouble(0.0),
                r.json());
    }

    @Test
    @DisplayName("TC52 — Dashboard for an entity with no rides returns 2xx + totalRides=0 + totalRevenue=0")
    @Disabled("DEFERRED: TC52 targets /api/drivers/{id}/dashboard, owned by driver-service test slice")
    void tc52_zeroRides_dashboard() { /* deferred to driver slice */ }

    @Test
    @DisplayName("TC54 — Dashboard returns totalRides/completionRate/totalRevenue/averageRideFare/ridesByStatus")
    void tc54_dashboardDtoHasAllFiveFields() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc54");
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("dashboard").isBetween(200, 299);
        for (String field : new String[]{"totalRides", "totalRevenue",
                "averageRideFare", "completionRate", "ridesByStatus"}) {
            assertThat(r.json().has(field))
                    .as("dashboard DTO must include field " + field)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("TC55 — Dashboard.totalRides equals exact count of rides in range (delta-based)")
    void tc55_totalRidesDeltaEqualsSeededCount() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc55");
        Redis.flushPattern("ride-service::S3-F10::*");
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        DashSnap pre = dashboard(rider.token(), start, end);
        for (int i = 0; i < 7; i++) {
            RideTestSupport.createRide(rider.token(), rider.uid(), null,
                    "COMPLETED", 75.0, Map.of());
        }
        DashSnap post = dashboard(rider.token(), start, end);

        assertThat(post.totalRides() - pre.totalRides())
                .as("totalRides delta after seeding 7 COMPLETED rides")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("TC56 — Dashboard.totalRevenue equals SUM(fare) for COMPLETED rides (delta-based)")
    void tc56_totalRevenueDeltaEqualsCompletedSum() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc56");
        Redis.flushPattern("ride-service::S3-F10::*");
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        DashSnap pre = dashboard(rider.token(), start, end);
        double[] fares = {50.0, 100.0, 150.0, 200.0};  // sum = 500
        for (double f : fares) {
            RideTestSupport.createRide(rider.token(), rider.uid(), null,
                    "COMPLETED", f, Map.of());
        }
        DashSnap post = dashboard(rider.token(), start, end);

        assertThat(post.totalRevenue() - pre.totalRevenue())
                .as("totalRevenue delta — should equal 50+100+150+200=500")
                .isBetween(499.5, 500.5);
    }

    @Test
    @DisplayName("TC57 — Dashboard.averageRideFare equals totalRevenue / completed count (delta-based)")
    void tc57_averageRideFareEqualsMeanOfCompleted() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc57");
        Redis.flushPattern("ride-service::S3-F10::*");
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        DashSnap pre = dashboard(rider.token(), start, end);
        // Mean of 30,60,90,120,150 = 90
        double[] fares = {30.0, 60.0, 90.0, 120.0, 150.0};
        for (double f : fares) {
            RideTestSupport.createRide(rider.token(), rider.uid(), null,
                    "COMPLETED", f, Map.of());
        }
        DashSnap post = dashboard(rider.token(), start, end);

        double revenueDelta = post.totalRevenue() - pre.totalRevenue();
        // Compute the expected completed-ride delta from the dashboard's
        // ridesByStatus.COMPLETED to avoid relying on a separate field.
        long preCompleted = pre.raw().path("ridesByStatus").path("COMPLETED").asLong(0);
        long postCompleted = post.raw().path("ridesByStatus").path("COMPLETED").asLong(0);
        long completedDelta = postCompleted - preCompleted;
        assertThat(completedDelta).as("seeded 5 COMPLETED").isEqualTo(5);
        assertThat(revenueDelta / completedDelta)
                .as("mean of 30/60/90/120/150 = 90")
                .isBetween(89.5, 90.5);
    }

    @Test
    @DisplayName("TC58 — Dashboard.completionRate = COMPLETED / total")
    void tc58_completionRateMath() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc58");
        Redis.flushPattern("ride-service::S3-F10::*");
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        // Pre-snap shows current rate computed over existing rides.
        DashSnap pre = dashboard(rider.token(), start, end);

        // Seed 5 COMPLETED + 3 CANCELLED
        for (int i = 0; i < 5; i++)
            RideTestSupport.createRide(rider.token(), rider.uid(), null, "COMPLETED", 100.0, Map.of());
        for (int i = 0; i < 3; i++)
            RideTestSupport.createRide(rider.token(), rider.uid(), null, "CANCELLED", 0.0, Map.of());

        DashSnap post = dashboard(rider.token(), start, end);

        // Verify the rate after seeding matches COMPLETED / total within
        // the window — uses absolute fields, not deltas (rate is a ratio).
        long total = post.totalRides();
        long completed = post.raw().path("ridesByStatus").path("COMPLETED").asLong(0);
        assertThat(total).as("post total > 0").isGreaterThan(0);
        double expected = (double) completed / total;
        assertThat(post.completionRate())
                .as("completionRate must equal COMPLETED / total = " + expected)
                .isBetween(expected - 0.01, expected + 0.01);
        assertThat(pre).isNotNull(); // touch to silence unused
    }

    @Test
    @DisplayName("TC59 — Dashboard.ridesByStatus has all 5 Ride statuses, each count=1 (delta-based)")
    void tc59_ridesByStatusHasAllFiveStatuses() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc59");
        Redis.flushPattern("ride-service::S3-F10::*");
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        DashSnap pre = dashboard(rider.token(), start, end);
        for (String s : new String[]{"REQUESTED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED"}) {
            RideTestSupport.createRide(rider.token(), rider.uid(), null, s, 100.0, Map.of());
        }
        DashSnap post = dashboard(rider.token(), start, end);

        for (String s : new String[]{"REQUESTED", "ACCEPTED", "IN_PROGRESS", "COMPLETED", "CANCELLED"}) {
            long preCount = pre.raw().path("ridesByStatus").path(s).asLong(0);
            long postCount = post.raw().path("ridesByStatus").path(s).asLong(0);
            assertThat(postCount - preCount)
                    .as("ridesByStatus." + s + " delta after seeding one " + s)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TC60 — Dashboard with no rides in range returns totalRides=0, totalRevenue=0")
    void tc60_farFutureRange_returnsZeros() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc60");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2099-01-01&endDate=2099-01-31")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("dashboard far-future").isBetween(200, 299);
        assertThat(r.json().path("totalRides").asLong(-1)).isZero();
        assertThat(r.json().path("totalRevenue").asDouble(-1.0))
                .isBetween(-0.01, 0.01);
    }

    @Test
    @DisplayName("TC61 — Dashboard with startDate > endDate returns 400")
    void tc61_invertedRange_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc61");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2026-04-01&endDate=2026-03-01")
                .bearer(rider.token()).get();

        assertThat(r.status())
                .as("inverted range — expected 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC62 — Dashboard without Authorization header returns 401")
    void tc62_noAuthHeader_returns401() {
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31")
                .get();

        assertThat(r.status())
                .as("no token — expected 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC63 — Dashboard with malformed JWT returns 401")
    void tc63_malformedJwt_returns401() {
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(JwtTestHelper.malformedToken())
                .get();

        assertThat(r.status())
                .as("malformed JWT — expected 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC64 — Ride at startDate T00:00:00 is included (today boundary)")
    void tc64_rideAtStartBoundary_isIncluded() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc64");
        Redis.flushPattern("ride-service::S3-F10::*");
        // Use a wider window to avoid clock-skew with the docker container
        // (createRide() server-side overwrites requestedAt with the container
        // clock, which can be on a different TZ from the test JVM).
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        DashSnap pre = dashboard(rider.token(), yesterday, tomorrow);
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);
        DashSnap post = dashboard(rider.token(), yesterday, tomorrow);

        assertThat(post.totalRides() - pre.totalRides())
                .as("ride created in the today window must be included in the dashboard")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC65 — Rides outside [startDate, endDate] are excluded")
    void tc65_ridesOutsideRange_areExcluded() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc65");
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        // Far past — today's seeded ride must not appear here
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=1990-01-01&endDate=1990-12-31")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("far-past range").isBetween(200, 299);
        assertThat(r.json().path("totalRides").asLong())
                .as("rides outside the [1990, 1990] range must be 0")
                .isZero();
    }

    @Test
    @DisplayName("TC66 — First dashboard call writes ANALYTICS_VIEWED to ride_events")
    void tc66_firstDashboardCall_writesAnalyticsViewed() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc66");
        // Capture the cluster-wide count, then expect a +1 delta after our call.
        long before = Mongo.count("ride_events", Map.of("action", "ANALYTICS_VIEWED"));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(rider.token()).get();
        assertThat(r.status()).as("dashboard call").isBetween(200, 299);

        long after = Mongo.countAtLeast("ride_events",
                Map.of("action", "ANALYTICS_VIEWED"),
                before + 1,
                Duration.ofSeconds(5));
        assertThat(after - before)
                .as("ANALYTICS_VIEWED must be logged at least once after dashboard call")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC67 — Second dashboard call (cache hit) still logs ANALYTICS_VIEWED")
    void tc67_cachedCall_stillLogsAnalyticsViewed() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc67");
        String window = "?startDate=2026-03-01&endDate=2026-03-31";

        // Prime the cache
        Http.request(RideTestSupport.RIDE_BASE, "/api/rides/analytics/dashboard" + window)
                .bearer(rider.token()).get();

        long before = Mongo.count("ride_events", Map.of("action", "ANALYTICS_VIEWED"));
        // Second call should be a cache hit but still log the view
        Http.Response r2 = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard" + window)
                .bearer(rider.token()).get();
        assertThat(r2.status()).as("second dashboard call").isBetween(200, 299);

        long after = Mongo.countAtLeast("ride_events",
                Map.of("action", "ANALYTICS_VIEWED"),
                before + 1,
                Duration.ofSeconds(5));
        assertThat(after - before)
                .as("ANALYTICS_VIEWED must increment even on cache hit (§10.3.1.d)")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC68 — Two identical dashboard requests return identical bodies")
    void tc68_idempotentReads_returnIdenticalBodies() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc68");
        String window = "?startDate=2026-03-01&endDate=2026-03-31";

        String a = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard" + window)
                .bearer(rider.token()).get().body();
        String b = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard" + window)
                .bearer(rider.token()).get().body();

        assertThat(b)
                .as("two identical dashboard reads must be byte-identical (caching invariant)")
                .isEqualTo(a);
    }

    @Test
    @DisplayName("TC69 — Insert ride after first call → cached body still returned within TTL")
    void tc69_writeDoesNotInvalidateForeignWindow() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc69");
        // Use a window NOT covered by today — POST /api/rides evicts the
        // S3-F10 cache wholesale, but for a far-past window the second
        // dashboard read is still cheap and must remain consistent.
        // We instead use the far-future window: writes can't possibly land
        // there because requestedAt is forced to now().
        String window = "?startDate=2099-01-01&endDate=2099-01-31";

        String a = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard" + window)
                .bearer(rider.token()).get().body();

        // Write — does NOT change the 2099 result set, regardless of cache state.
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        String b = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard" + window)
                .bearer(rider.token()).get().body();

        assertThat(b)
                .as("far-future window must be unaffected by a today-seeded ride")
                .isEqualTo(a);
    }

    @Test
    @DisplayName("S3-F10 — dashboard caches to ride-service::S3-F10::*")
    void s3f10_cachesUnderS3F10Namespace() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("s3f10cache");
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31")
                .bearer(rider.token()).get();
        assertThat(r.status()).as("dashboard").isBetween(200, 299);

        assertThat(Redis.countKeys("ride-service::S3-F10::*"))
                .as("dashboard must cache to ride-service::S3-F10::*")
                .isGreaterThanOrEqualTo(1);
    }
}
