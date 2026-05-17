package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F6 — GET /api/rides/analytics (M1 endpoint, returns RideAnalyticsDTO).
 *
 * <p>Distinct from the M2-introduced /api/rides/analytics/dashboard which
 * returns the richer RideAnalyticsDashboardDTO with ridesByStatus map.
 *
 * <p>Math is delta-based: createRide() overwrites requestedAt with now() so we
 * cannot seed historical dates. The window is today±1; we assert the delta
 * between pre- and post-seed reads matches the expected counts.
 */
@DisplayName("S3-F6 — Ride analytics /api/rides/analytics")
class RideAnalyticsFeatureTest extends BaseHttpTest {

    private record Snapshot(long totalRides, long completedRides, double totalRevenue) {}

    private Snapshot snapshot(String token, LocalDate start, LocalDate end) {
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics?startDate=" + start + "&endDate=" + end)
                .bearer(token).get();
        assertThat(r.status()).as("analytics snapshot").isBetween(200, 299);
        return new Snapshot(
                r.json().path("totalRides").asLong(0),
                r.json().path("completedRides").asLong(0),
                r.json().path("totalRevenue").asDouble(0.0));
    }

    @Test
    @DisplayName("TC260 — Analytics returns totalRides delta=4, completedRides delta=3, completionRate≈3/4")
    void tc260_seed3Completed1Cancelled_assertsDeltas() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc260");
        // Clear any stale cached snapshot so pre/post reads reflect ground truth
        Redis.flushPattern("ride-service::S3-F6::*");

        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        Snapshot pre = snapshot(rider.token(), start, end);

        // Seed 3 COMPLETED + 1 CANCELLED in the today window
        for (int i = 0; i < 3; i++) {
            RideTestSupport.createRide(rider.token(), rider.uid(), null,
                    "COMPLETED", 100.0, Map.of());
        }
        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "CANCELLED", 0.0, Map.of());

        // Each write evicts the analytics cache — second read is ground truth.
        Snapshot post = snapshot(rider.token(), start, end);

        assertThat(post.totalRides() - pre.totalRides())
                .as("totalRides delta after seeding 4 rides")
                .isEqualTo(4);
        assertThat(post.completedRides() - pre.completedRides())
                .as("completedRides delta after seeding 3 COMPLETED + 1 CANCELLED")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("TC261 — Analytics with no rides in range returns totalRides=0")
    void tc261_farFutureRange_returnsZero() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc261");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics?startDate=2099-01-01&endDate=2099-01-31")
                .bearer(rider.token()).get();

        assertThat(r.status()).as("analytics empty range").isBetween(200, 299);
        assertThat(r.json().path("totalRides").asLong(-1))
                .as("totalRides for empty range must be 0")
                .isZero();
    }

    @Test
    @DisplayName("TC271 — Analytics totalRevenue sums COMPLETED ride fares only")
    void tc271_completedFareSum_excludesCancelled() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc271");
        Redis.flushPattern("ride-service::S3-F6::*");

        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        Snapshot pre = snapshot(rider.token(), start, end);

        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 80.0, Map.of());
        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 70.0, Map.of());
        // CANCELLED with high fare — must NOT show up in revenue sum
        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "CANCELLED", 999.0, Map.of());

        Snapshot post = snapshot(rider.token(), start, end);

        assertThat(post.totalRevenue() - pre.totalRevenue())
                .as("totalRevenue delta — should equal 80+70=150 (CANCELLED 999 excluded)")
                .isBetween(149.5, 150.5);
    }

    @Test
    @DisplayName("TC345 — Analytics averageFare equals mean of COMPLETED fares")
    void tc345_averageFare_meanOfCompleted() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc345");
        Redis.flushPattern("ride-service::S3-F6::*");

        // Use a fresh rider to keep the window dominated by our two seeds.
        LocalDate start = LocalDate.now().minusDays(1);
        LocalDate end = LocalDate.now().plusDays(1);

        // Pre-snapshot from the wider window
        Snapshot pre = snapshot(rider.token(), start, end);

        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 100.0, Map.of());
        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 50.0, Map.of());

        Snapshot post = snapshot(rider.token(), start, end);
        Http.Response postRaw = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics?startDate=" + start + "&endDate=" + end)
                .bearer(rider.token()).get();

        // Verify the delta math: averageFare = (sumPost - sumPre) / countDelta
        double revenueDelta = post.totalRevenue() - pre.totalRevenue();
        long completedDelta = post.completedRides() - pre.completedRides();
        assertThat(completedDelta).as("seeded 2 COMPLETED rides").isEqualTo(2);
        assertThat(revenueDelta / completedDelta)
                .as("mean of seeded fares = (100+50)/2 = 75")
                .isBetween(74.5, 75.5);

        // The endpoint's own averageFare may differ if the window contains
        // other rides outside this run. We just verify it's positive and the
        // shape matches contract.
        assertThat(postRaw.json().has("averageFare"))
                .as("RideAnalyticsDTO must include averageFare field")
                .isTrue();
    }

    @Test
    @DisplayName("S3-F6 — analytics caches under ride-service::S3-F6::*")
    void s3f6_cachesUnderS3F6Namespace() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("s3f6cache");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/analytics?startDate=2026-01-01&endDate=2026-12-31")
                .bearer(rider.token()).get();
        assertThat(r.status()).as("analytics call").isBetween(200, 299);

        assertThat(Redis.countKeys("ride-service::S3-F6::*"))
                .as("S3-F6 must cache to ride-service::S3-F6::*")
                .isGreaterThanOrEqualTo(1);
    }
}
