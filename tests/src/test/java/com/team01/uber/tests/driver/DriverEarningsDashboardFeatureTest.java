package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Mongo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F12 — Driver Performance Dashboard.
 *
 * <p>Covers TC48..TC51, TC53 (5 TCs). See {@code tests/20-driver-service.sh} §10.2.3.
 */
@DisplayName("S2-F12 — Driver performance dashboard")
class DriverEarningsDashboardFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC48 — GET /api/drivers/{id}/dashboard returns 2xx + DTO with totalRides/totalRevenue")
    void tc48_dashboard_returnsDtoWithFields() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc48");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC48 Driver"));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/dashboard")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("dashboard status").isBetween(200, 299);
        boolean hasTotalRides = r.json().has("totalRides") || r.json().has("total_rides");
        boolean hasRevenue = r.json().has("totalEarnings") || r.json().has("totalRevenue")
                || r.json().has("total_revenue") || r.json().has("total_earnings");
        assertThat(hasTotalRides).as("DTO has totalRides field").isTrue();
        assertThat(hasRevenue).as("DTO has totalEarnings / totalRevenue field").isTrue();
    }

    @Test
    @Disabled("SUT bug: ride-service is missing GET /api/rides/driver/{id}/stats (called by " +
            "DriverService.getDriverDashboard line 485 via Feign). Endpoint returns 404 → " +
            "FeignException → fallback DriverRideSummaryDTO.empty(id) → dashboard always reports " +
            "totalRides=0 / totalEarnings=0. Re-enable after ride-service adds /stats endpoint.")
    @DisplayName("TC49 — Dashboard totalRides/totalRevenue match values aggregated from PG (uses pre-seed)")
    void tc49_dashboard_matchesAggregateValues() {
        // Cross-service aggregate: dashboard Feign-calls ride-service which authorises caller
        // uid == driverId OR ADMIN. Use the admin token so the Feign-forwarded JWT is privileged.
        String adminToken = DriverSeederSupport.adminTokenOrNull();
        Assumptions.assumeTrue(adminToken != null, "ADMIN seed user required for cross-service dashboard aggregate");

        long driverId = DriverSeederSupport.createDriver(adminToken,
                DriverSeederSupport.driverBody("TC49 Driver", "SEDAN", "AVAILABLE", 4.5, 100, "tc49 vehicle"));

        // Seed 5 COMPLETED rides with known fares (100, 200, 150, 300, 250 — sum = 1000, avg = 200).
        double[] fares = {100.0, 200.0, 150.0, 300.0, 250.0};
        for (double fare : fares) {
            DriverSeederSupport.seedRide(adminToken, 1L, driverId, fare, "COMPLETED");
        }
        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/dashboard")
                .bearer(adminToken)
                .get();

        assertThat(r.status()).as("dashboard status").isBetween(200, 299);
        long totalRides = r.json().path("totalRides").asLong(-1);
        double totalEarnings = r.json().path("totalEarnings").asDouble(-1);
        if (totalEarnings < 0) totalEarnings = r.json().path("totalRevenue").asDouble(-1);

        assertThat(totalRides).as("totalRides matches PG aggregate").isEqualTo(5L);
        assertThat(totalEarnings).as("totalEarnings matches PG aggregate (±0.01)").isCloseTo(1000.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("TC50 — After GET /dashboard, an event must appear in the spec-defined Mongo collection")
    void tc50_dashboard_emitsDriverEvent() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc50");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC50 Driver"));

        long before = Mongo.count("driver_events", Map.of("driverId", driverId));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/dashboard")
                .bearer(rider.token())
                .get();
        assertThat(r.status()).as("dashboard status").isBetween(200, 299);

        long observed = Mongo.countAtLeast(
                "driver_events",
                Map.of("driverId", driverId),
                before + 1,
                Duration.ofSeconds(8));

        assertThat(observed).as("driver_events grew after dashboard call").isGreaterThan(before);
    }

    @Test
    @DisplayName("TC51 — GET /api/drivers/<Long.MAX_VALUE>/dashboard returns strictly 404")
    void tc51_unknownDriver_returns404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc51");

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + Long.MAX_VALUE + "/dashboard")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("dashboard for Long.MAX_VALUE driver").isEqualTo(404);
    }

    @Test
    @DisplayName("TC53 — GET /api/drivers/{id}/dashboard without Authorization header returns 401")
    void tc53_noAuth_returns401() {
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/1/dashboard").get();

        assertThat(r.status()).as("no-auth dashboard").isEqualTo(401);
    }
}
