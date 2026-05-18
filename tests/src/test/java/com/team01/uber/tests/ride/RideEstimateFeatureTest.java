package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F3 — Fare estimate at POST /api/rides/estimate.
 *
 * <p>Estimate is semantically a read but uses POST because the input is a
 * structured DTO (coords); response is cached by request body hash.
 */
@DisplayName("S3-F3 — Fare estimate /api/rides/estimate")
class RideEstimateFeatureTest extends BaseHttpTest {

    private Map<String, Object> coords(double pLat, double pLng, double dLat, double dLng) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickupLatitude", pLat);
        body.put("pickupLongitude", pLng);
        body.put("dropoffLatitude", dLat);
        body.put("dropoffLongitude", dLng);
        return body;
    }

    @Test
    @DisplayName("TC254 — POST estimate returns estimatedDistance + estimatedFare > 0")
    void tc254_validCoords_returnsPositiveDistanceAndFare() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc254");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/estimate")
                .bearer(rider.token())
                .json(coords(30.0, 31.0, 30.1, 31.1))
                .post();

        assertThat(r.status()).as("estimate happy path").isBetween(200, 299);
        assertThat(r.json().path("estimatedDistance").asDouble())
                .as("estimatedDistance must be > 0 for non-trivial route")
                .isGreaterThan(0.0);
        assertThat(r.json().path("estimatedFare").asDouble())
                .as("estimatedFare must be > 0 for non-trivial route")
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("TC255 — Estimate missing dropoff coords returns 400")
    void tc255_missingDropoff_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc255");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickupLatitude", 30.0);
        body.put("pickupLongitude", 31.0);
        // intentionally omit dropoff*
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/estimate")
                .bearer(rider.token())
                .json(body)
                .post();

        assertThat(r.status())
                .as("estimate missing dropoff — expected 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC274 — Estimate with identical pickup/dropoff returns near-zero distance")
    void tc274_identicalCoords_returnsNearZeroDistance() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc274");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/estimate")
                .bearer(rider.token())
                .json(coords(30.0, 31.0, 30.0, 31.0))
                .post();

        assertThat(r.status()).as("estimate same coords").isBetween(200, 299);
        assertThat(r.json().path("estimatedDistance").asDouble())
                .as("estimatedDistance with identical coords must be ~0")
                .isLessThan(0.001);
    }

    @Test
    @DisplayName("TC343 — Fare-estimate response includes surgeMultiplier")
    void tc343_response_includesSurgeMultiplier() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc343");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/estimate")
                .bearer(rider.token())
                .json(coords(30.0, 31.0, 30.1, 31.1))
                .post();

        assertThat(r.status()).as("estimate happy path").isBetween(200, 299);
        assertThat(r.json().has("surgeMultiplier"))
                .as("estimate DTO must include surgeMultiplier (S3-F3 contract)")
                .isTrue();
        assertThat(r.json().path("surgeMultiplier").asDouble())
                .as("surgeMultiplier must be >= 1.0 (1.0=no-surge baseline)")
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("S3-F3 — estimate caches under ride-service::S3-F3::*")
    void s3f3_cachesUnderS3F3() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("s3f3cache");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/estimate")
                .bearer(rider.token())
                .json(coords(30.0, 31.0, 30.1, 31.1))
                .post();
        assertThat(r.status()).as("estimate call").isBetween(200, 299);

        assertThat(Redis.countKeys("ride-service::S3-F3::*"))
                .as("estimate must cache to ride-service::S3-F3::*")
                .isGreaterThanOrEqualTo(1);
    }
}
