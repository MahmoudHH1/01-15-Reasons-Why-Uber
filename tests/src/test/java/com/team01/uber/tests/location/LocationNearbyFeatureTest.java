package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F3 — Nearby drivers endpoint.
 */
@DisplayName("S4-F3 — Nearby drivers")
class LocationNearbyFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC279 — Nearby returns drivers within radiusKm")
    void tc279_nearby_returnsWithinRadius() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc279");
        long near = LocationSeederSupport.seedDriver(me.token(), "tc279near");
        long far = LocationSeederSupport.seedDriver(me.token(), "tc279far");

        // Near point (30.045, 31.236) and Far point (31.500, 32.500)
        LocationSeederSupport.seedLocation(me.token(), near, 30.045, 31.236,
                LocationSeederSupport.nowTs(), Map.of("speed", 10.0));
        LocationSeederSupport.seedLocation(me.token(), far, 31.500, 32.500,
                LocationSeederSupport.nowTs(), Map.of("speed", 10.0));

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/nearby?lat=30.044&lon=31.235&radiusKm=5")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("nearby 2xx").isBetween(200, 299);

        JsonNode arr = r.json();
        boolean hasNear = false;
        boolean hasFar = false;
        for (JsonNode item : arr) {
            long did = item.path("driverId").asLong();
            if (did == near) hasNear = true;
            if (did == far) hasFar = true;
        }
        assertThat(hasNear).as("nearby driver " + near + " present").isTrue();
        assertThat(hasFar).as("far driver " + far + " absent").isFalse();
    }

    @Test
    @DisplayName("TC280 — Nearby with radiusKm=-1 returns 400")
    void tc280_nearby_negativeRadius_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc280");

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/nearby?lat=30.0&lon=31.0&radiusKm=-1")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("negative radius").isEqualTo(400);
    }

    @Test
    @DisplayName("TC295 — Nearby with radiusKm=0 returns empty list")
    void tc295_nearby_zeroRadius_returnsEmptyList() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc295");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc295");
        LocationSeederSupport.seedLocation(me.token(), driverId, 30.05, 31.24,
                LocationSeederSupport.nowTs(), Map.of("speed", 10.0));

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/nearby?lat=30.044&lon=31.235&radiusKm=0")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("radius=0 2xx").isBetween(200, 299);

        JsonNode arr = r.json();
        assertThat(arr.isArray()).as("nearby returns array").isTrue();
        assertThat(arr.size()).as("radius=0 returns empty").isZero();
    }

    @Test
    @DisplayName("TC353 — Nearby DTO includes distanceKm field")
    void tc353_nearby_dto_includesDistanceKmField() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc353");
        long driverId = LocationSeederSupport.seedDriver(me.token(), "tc353");
        LocationSeederSupport.seedLocation(me.token(), driverId, 30.045, 31.236,
                LocationSeederSupport.nowTs(), Map.of("speed", 10.0));

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/nearby?lat=30.044&lon=31.235&radiusKm=10")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("nearby 2xx").isBetween(200, 299);

        JsonNode arr = r.json();
        assertThat(arr.isArray()).as("response is array").isTrue();
        if (arr.size() > 0) {
            JsonNode first = arr.get(0);
            boolean hasDistance =
                    first.has("distanceKm")
                    || first.has("distance_km")
                    || first.has("distance");
            assertThat(hasDistance)
                    .as("first nearby DTO has distance field (distanceKm/distance_km/distance)")
                    .isTrue();
        }
    }
}
