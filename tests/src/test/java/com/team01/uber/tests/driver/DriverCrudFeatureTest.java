package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplemental coverage of basic Driver CRUD + cache key shape. These are not in the public TSV
 * slice but mirror the bash {@code CRUD Driver} section in {@code tests/20-driver-service.sh}
 * (§4.4.2 cache-by-id, §4.4.2 list-not-cached). Acts as a regression net for the M2 carry-over
 * invariants.
 */
@DisplayName("Driver CRUD — supplemental cache + lifecycle")
class DriverCrudFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("CRUD POST then GET-by-id populates driver-service::driver::{id}")
    void crud_getById_populatesEntityCache() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("crud_cache");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("CRUD Cache"));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(rider.token())
                .get();
        assertThat(r.status()).as("GET driver-by-id status").isBetween(200, 299);

        int cached = Redis.countKeys("driver-service::driver::" + driverId);
        assertThat(cached)
                .as("driver-service::driver::" + driverId + " key present")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CRUD PUT invalidates driver-service::driver::{id}")
    void crud_put_invalidatesEntityCache() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("crud_inv");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("CRUD Inv"));

        // Warm the entity cache.
        Http.request(DRIVER_BASE, "/api/drivers/" + driverId).bearer(token).get();
        assertThat(Redis.countKeys("driver-service::driver::" + driverId))
                .as("entity cache warmed").isGreaterThanOrEqualTo(1);

        // Update via PUT.
        Map<String, Object> body = DriverSeederSupport.driverBody("CRUD Inv Renamed");
        Http.Response put = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token).json(body).put();
        assertThat(put.status()).as("PUT update").isBetween(200, 299);

        assertThat(Redis.countKeys("driver-service::driver::" + driverId))
                .as("entity cache evicted after PUT")
                .isZero();
    }

    @Test
    @DisplayName("DELETE removes the driver and returns 2xx; subsequent GET is 404")
    void delete_returns2xx_thenGetIs404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("crud_del");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("CRUD Del"));

        Http.Response del = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token).delete();
        assertThat(del.status()).as("DELETE driver").isBetween(200, 299);

        Http.Response get = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token).get();
        assertThat(get.status()).as("GET after DELETE").isEqualTo(404);
    }

    @Test
    @DisplayName("GET /api/drivers/health returns OK without auth")
    void health_publicNoAuth() {
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/health").get();
        assertThat(r.status()).as("health status").isBetween(200, 299);
        assertThat(r.body()).as("health body").isEqualTo("OK");
    }
}
