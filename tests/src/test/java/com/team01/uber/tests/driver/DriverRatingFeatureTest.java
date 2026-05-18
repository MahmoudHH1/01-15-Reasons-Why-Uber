package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F7 — Rate driver endpoint.
 *
 * <p>Covers TC235, TC236, TC237, TC340 (4 TCs).
 */
@DisplayName("S2-F7 — Rate driver")
class DriverRatingFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC235 — POST /rate updates driver rating + totalRatings")
    void tc235_rate_incrementsTotalRatings() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc235");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC235 Driver", "SEDAN", "AVAILABLE", 4.0, 1, ""));

        // Seed a COMPLETED ride.
        long rideId = DriverSeederSupport.seedRide(token, rider.uid(), driverId, 100.0, "COMPLETED");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/rate")
                .bearer(token)
                .json(Map.of("rideId", rideId, "rating", 5))
                .post();

        assertThat(r.status()).as("rate driver").isBetween(200, 299);

        // Verify totalRatings was incremented.
        Http.Response read = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token)
                .get();
        assertThat(read.status()).as("re-read driver").isBetween(200, 299);
        assertThat(read.json().path("totalRatings").asInt())
                .as("totalRatings incremented to 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("TC236 — Rate with rating=6 returns 400")
    void tc236_rate_outOfRange_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc236");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC236 Driver"));
        long rideId = DriverSeederSupport.seedRide(token, rider.uid(), driverId, 75.0, "COMPLETED");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/rate")
                .bearer(token)
                .json(Map.of("rideId", rideId, "rating", 6))
                .post();

        assertThat(r.status()).as("rating=6 must be 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC237 — Rate of REQUESTED ride returns 400")
    void tc237_rate_requestedRide_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc237");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC237 Driver"));
        long rideId = DriverSeederSupport.seedRide(token, rider.uid(), driverId, 80.0, "REQUESTED",
                "2030-01-01T10:00:00", null);

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/rate")
                .bearer(token)
                .json(Map.of("rideId", rideId, "rating", 4))
                .post();

        assertThat(r.status()).as("rating non-COMPLETED ride must be 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC340 — Rate driver for ride that belongs to another driver returns 400")
    void tc340_rate_wrongDriver_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc340");
        String token = rider.token();
        long d1 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC340 D1"));
        long d2 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC340 D2"));

        // Ride is assigned to d1.
        long rideId = DriverSeederSupport.seedRide(token, rider.uid(), d1, 60.0, "COMPLETED");

        // But we try to rate d2 with d1's ride.
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + d2 + "/rate")
                .bearer(token)
                .json(Map.of("rideId", rideId, "rating", 4))
                .post();

        assertThat(r.status())
                .as("rate driver for foreign-owned ride must be 400")
                .isEqualTo(400);
    }
}
