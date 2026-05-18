package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F2 — Driver vehicle PUT (merges into JSONB).
 *
 * <p>Covers TC224, TC225, TC243, TC370 (4 TCs).
 */
@DisplayName("S2-F2 — Driver vehicle PUT (JSONB merge)")
class DriverVehicleFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC224 — PUT vehicle merges new keys into existing vehicleDetails JSONB")
    void tc224_putVehicle_mergesKeys() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc224");
        String token = rider.token();

        Map<String, Object> body = DriverSeederSupport.driverBody("TC224 Driver");
        // Override vehicleDetails to known initial state.
        @SuppressWarnings("unchecked")
        Map<String, Object> vd = (Map<String, Object>) body.get("vehicleDetails");
        vd.put("make", "Toyota");
        vd.put("model", "Camry");
        long driverId = DriverSeederSupport.createDriver(token, body);

        Http.Response put = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/vehicle")
                .bearer(token)
                .json(Map.of("color", "Red", "year", 2024))
                .put();

        assertThat(put.status()).as("PUT vehicle").isBetween(200, 299);
        var merged = put.json().path("vehicleDetails");
        assertThat(merged.path("make").asText()).as("make preserved").isEqualTo("Toyota");
        assertThat(merged.path("model").asText()).as("model preserved").isEqualTo("Camry");
        assertThat(merged.path("color").asText()).as("color added").isEqualTo("Red");
        assertThat(merged.path("year").asInt()).as("year added").isEqualTo(2024);
    }

    @Test
    @DisplayName("TC225 — Vehicle PUT for non-existent driver returns 404")
    void tc225_vehiclePut_unknownDriver_returns404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc225");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/999999/vehicle")
                .bearer(rider.token())
                .json(Map.of("color", "Blue"))
                .put();

        assertThat(r.status()).as("PUT vehicle unknown driver").isEqualTo(404);
    }

    @Test
    @DisplayName("TC243 — Vehicle PUT with empty body returns 400")
    void tc243_vehiclePut_emptyBody_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc243");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC243 Driver"));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/vehicle")
                .bearer(rider.token())
                .json(Map.of())
                .put();

        assertThat(r.status()).as("PUT vehicle empty body").isEqualTo(400);
    }

    @Test
    @DisplayName("TC370 — Vehicle PUT for unknown driver returns 404")
    void tc370_vehiclePut_unknownDriver_extras() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc370");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/999999/vehicle")
                .bearer(rider.token())
                .json(Map.of("color", "Red"))
                .put();

        assertThat(r.status()).as("PUT vehicle unknown driver (extras TC370)").isEqualTo(404);
    }
}
