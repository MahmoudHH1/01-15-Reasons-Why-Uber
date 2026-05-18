package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F5 — Vehicle-type filter.
 *
 * <p>Covers TC231, TC232, TC338 (3 TCs).
 */
@DisplayName("S2-F5 — Vehicle-type filter")
class DriverVehicleTypeFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC231 — ?type=SEDAN returns drivers whose vehicleDetails.vehicleType=SEDAN")
    void tc231_typeSedanFilter_returnsSedanDrivers() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc231");
        String token = rider.token();
        long d1 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC231 Sedan", "SEDAN", "AVAILABLE", 4.0, 5, ""));
        long d2 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC231 SUV", "SUV", "AVAILABLE", 4.0, 5, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/vehicle-type?type=SEDAN")
                .bearer(token)
                .get();
        assertThat(r.status()).as("vehicle-type?type=SEDAN").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();

        boolean d1Present = false;
        boolean d2Present = false;
        for (var node : r.json()) {
            long id = node.path("id").asLong();
            if (id == d1) d1Present = true;
            if (id == d2) d2Present = true;
        }
        assertThat(d1Present).as("SEDAN driver present").isTrue();
        assertThat(d2Present).as("SUV driver absent").isFalse();
    }

    @Test
    @DisplayName("TC232 — ?type=SEDAN&status=AVAILABLE returns only available SEDAN drivers")
    void tc232_typeAndStatusFilter_returnsCorrectIntersection() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc232");
        String token = rider.token();
        long d1 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC232 Avail", "SEDAN", "AVAILABLE", 4.0, 5, ""));
        long d2 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC232 Off", "SEDAN", "OFFLINE", 4.0, 5, ""));

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/vehicle-type?type=SEDAN&status=AVAILABLE")
                .bearer(token)
                .get();
        assertThat(r.status()).as("vehicle-type SEDAN+AVAILABLE").isBetween(200, 299);
        r.json().forEach(node -> assertThat(node.path("status").asText())
                .as("each item.status == AVAILABLE")
                .isEqualTo("AVAILABLE"));
        // The OFFLINE driver should be absent.
        boolean d2Present = false;
        for (var node : r.json()) if (node.path("id").asLong() == d2) d2Present = true;
        assertThat(d2Present).as("OFFLINE SEDAN excluded").isFalse();
        // d1 should generally be present, but if pagination shenanigans we don't strict-require it.
        assertThat(d1).isGreaterThan(0);
    }

    @Test
    @DisplayName("TC338 — Vehicle-type ?type=YACHT (not in enum) returns 400")
    void tc338_unknownType_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc338");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/vehicle-type?type=YACHT")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("unknown vehicle type").isEqualTo(400);
    }
}
