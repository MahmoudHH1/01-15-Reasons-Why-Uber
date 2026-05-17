package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F3 — Driver earnings endpoint.
 *
 * <p>Covers TC226, TC227, TC228, TC244, TC337 (5 TCs).
 */
@DisplayName("S2-F3 — Driver earnings")
class DriverEarningsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC226 — Earnings sums COMPLETED-ride fares in date range")
    void tc226_earnings_sumsCompletedFares() {
        // The ride-service /driver/{id}/summary endpoint requires caller uid == driverId OR ADMIN.
        // Use the admin token so the driver-service Feign forwards a privileged JWT.
        String adminToken = DriverSeederSupport.adminTokenOrNull();
        Assumptions.assumeTrue(adminToken != null, "ADMIN seed user required for cross-service earnings aggregate");

        long driverId = DriverSeederSupport.createDriver(adminToken,
                DriverSeederSupport.driverBody("TC226 Driver"));

        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 100.0, "COMPLETED");
        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 150.0, "COMPLETED");
        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 50.0, "COMPLETED");
        try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/earnings?startDate=" + LocalDate.now().minusDays(1) + "&endDate=" + LocalDate.now().plusDays(1))
                .bearer(adminToken)
                .get();

        assertThat(r.status()).as("earnings status").isBetween(200, 299);
        double totalEarnings = r.json().path("totalEarnings").asDouble(-1);
        assertThat(totalEarnings).as("totalEarnings sums 3 COMPLETED rides")
                .isCloseTo(300.0, Offset.offset(0.5));
    }

    @Test
    @DisplayName("TC227 — Earnings excludes CANCELLED rides")
    void tc227_earnings_excludesCancelled() {
        String adminToken = DriverSeederSupport.adminTokenOrNull();
        Assumptions.assumeTrue(adminToken != null, "ADMIN seed user required for cross-service earnings aggregate");

        long driverId = DriverSeederSupport.createDriver(adminToken,
                DriverSeederSupport.driverBody("TC227 Driver"));

        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 100.0, "COMPLETED");
        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 999.0, "CANCELLED");
        try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/earnings?startDate=" + LocalDate.now().minusDays(1) + "&endDate=" + LocalDate.now().plusDays(1))
                .bearer(adminToken)
                .get();

        assertThat(r.status()).as("earnings status").isBetween(200, 299);
        double totalEarnings = r.json().path("totalEarnings").asDouble(-1);
        assertThat(totalEarnings).as("totalEarnings excludes cancelled fare")
                .isCloseTo(100.0, Offset.offset(0.5));
    }

    @Test
    @DisplayName("TC228 — Earnings for non-existent driver returns 404")
    void tc228_earnings_unknownDriver_returns404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc228");

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/999999/earnings?startDate=2026-01-01&endDate=2026-12-31")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("earnings for unknown driver").isEqualTo(404);
    }

    @Test
    @DisplayName("TC244 — Earnings with startDate > endDate returns 400")
    void tc244_earnings_invertedDates_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc244");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC244 Driver"));

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/earnings?startDate=2026-12-31&endDate=2026-01-01")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("inverted earnings date range").isEqualTo(400);
    }

    @Test
    @DisplayName("TC337 — Earnings DTO includes totalRides count")
    void tc337_earningsDto_includesTotalRides() {
        String adminToken = DriverSeederSupport.adminTokenOrNull();
        Assumptions.assumeTrue(adminToken != null, "ADMIN seed user required for cross-service earnings aggregate");

        long driverId = DriverSeederSupport.createDriver(adminToken,
                DriverSeederSupport.driverBody("TC337 Driver"));

        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 100.0, "COMPLETED");
        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 75.0, "COMPLETED");
        try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/earnings?startDate=" + LocalDate.now().minusDays(1) + "&endDate=" + LocalDate.now().plusDays(1))
                .bearer(adminToken)
                .get();

        assertThat(r.status()).as("earnings status").isBetween(200, 299);
        long totalRides = r.json().path("totalRides").asLong(-1);
        assertThat(totalRides).as("DTO includes totalRides=2").isEqualTo(2L);
    }
}
