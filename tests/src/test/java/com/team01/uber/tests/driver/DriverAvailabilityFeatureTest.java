package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F4 — Driver availability flip.
 *
 * <p>Covers TC229, TC230, TC246 (3 TCs).
 */
@DisplayName("S2-F4 — Driver availability")
class DriverAvailabilityFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC229 — PUT availability flips status to OFFLINE when no active rides")
    void tc229_availability_offlineWhenNoRides() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc229");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC229 Driver", "SEDAN", "AVAILABLE", 4.0, 5, ""));

        Http.Response put = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/availability")
                .bearer(token)
                .json(Map.of("status", "OFFLINE"))
                .put();
        assertThat(put.status()).as("flip to OFFLINE").isBetween(200, 299);

        Http.Response get = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token)
                .get();
        assertThat(get.json().path("status").asText())
                .as("PG persisted OFFLINE")
                .isEqualTo("OFFLINE");
    }

    @Test
    @DisplayName("TC230 — Availability OFFLINE blocked (400) by IN_PROGRESS ride")
    void tc230_availability_offlineBlockedByActiveRide() {
        // Driver-service Feign-calls ride-service /driver/{id}/active-count which authorises
        // caller uid == driverId OR ADMIN. We must use the admin token for the active-count
        // check to actually return > 0 and trigger the 400.
        String adminToken = DriverSeederSupport.adminTokenOrNull();
        Assumptions.assumeTrue(adminToken != null, "ADMIN seed user required for cross-service active-ride check");

        long driverId = DriverSeederSupport.createDriver(adminToken,
                DriverSeederSupport.driverBody("TC230 Driver", "SEDAN", "BUSY", 4.0, 5, ""));

        // Seed an IN_PROGRESS ride in the future to keep this driver busy.
        DriverSeederSupport.seedRide(adminToken, 1L, driverId, 50.0, "IN_PROGRESS",
                "2030-01-01T10:00:00", null);
        try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Http.Response put = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/availability")
                .bearer(adminToken)
                .json(Map.of("status", "OFFLINE"))
                .put();

        assertThat(put.status())
                .as("OFFLINE blocked by IN_PROGRESS ride")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC246 — Availability for non-existent driver returns 404")
    void tc246_availability_unknownDriver_returns404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc246");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/999999/availability")
                .bearer(rider.token())
                .json(Map.of("status", "OFFLINE"))
                .put();

        assertThat(r.status()).as("availability unknown driver").isEqualTo(404);
    }
}
