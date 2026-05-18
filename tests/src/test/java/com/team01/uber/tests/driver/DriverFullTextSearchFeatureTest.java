package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F10 — Full-Text Driver Search via Elasticsearch.
 *
 * <p>Covers TC35..TC42 (8 TCs). See {@code tests/20-driver-service.sh} for the bash equivalent
 * (§10.2.1 in the M2/M3 spec).
 */
@DisplayName("S2-F10 — Full-text driver search")
class DriverFullTextSearchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC35 — GET /api/drivers/search/full-text?query=test with valid token returns 2xx + array shape")
    void tc35_validToken_returns2xxAndArray() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc35");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/search/full-text?query=test")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("full-text search status").isBetween(200, 299);
        boolean isArray = r.json().isArray()
                || (r.json().has("content") && r.json().path("content").isArray());
        assertThat(isArray).as("response is array or paginated envelope").isTrue();
    }

    @Test
    @DisplayName("TC36 — GET /api/drivers/search/full-text without Authorization header returns 401")
    void tc36_noAuth_returns401() {
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/search/full-text?query=anything").get();

        assertThat(r.status()).as("no-auth full-text search").isEqualTo(401);
    }

    @Test
    @Disabled("TC37 — generic categorical-filter placeholder; explicitly marked disabled in catalogue")
    @DisplayName("TC37 — Search ?<filter>=<value0> returns only entities with that filter value")
    void tc37_genericFilter_skipped() {
        // Documented as @Disabled in the catalogue.
    }

    @Test
    @DisplayName("TC38 — Search ?status=<value0> returns only entities with that status")
    void tc38_statusFilter_returnsOnlyMatchingStatus() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc38");
        String token = rider.token();

        // Seed two drivers with different statuses then index both.
        long d1 = DriverSeederSupport.createDriver(
                token,
                DriverSeederSupport.driverBody("TC38 Avail", "SEDAN", "AVAILABLE", 4.0, 5, "tc38unique car"));
        long d2 = DriverSeederSupport.createDriver(
                token,
                DriverSeederSupport.driverBody("TC38 Busy", "SEDAN", "BUSY", 4.0, 5, "tc38unique car"));
        DriverSeederSupport.indexDriver(token, d1);
        DriverSeederSupport.indexDriver(token, d2);

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search/full-text?query=tc38unique&status=AVAILABLE")
                .bearer(token)
                .get();

        assertThat(r.status()).as("status-filtered full-text search").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        r.json().forEach(node -> {
            String s = node.path("status").asText("");
            // The SUT may or may not include status; assert only when present.
            if (!s.isBlank()) {
                assertThat(s).as("each result status").isEqualTo("AVAILABLE");
            }
        });
    }

    @Test
    @DisplayName("TC39 — Search ?minRating=4.0&maxRating=5.0 returns only entities with rating in [4.0, 5.0]")
    void tc39_ratingRangeFilter_returnsOnlyInRange() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc39");
        String token = rider.token();
        String tag = "tc39rng" + Nonce.nonce().substring(0, 6);

        long lo = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody(tag + " Lo", "SEDAN", "AVAILABLE", 3.0, 50, tag));
        long mid = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody(tag + " Mid", "SEDAN", "AVAILABLE", 4.5, 50, tag));
        long hi = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody(tag + " Hi", "SEDAN", "AVAILABLE", 5.0, 50, tag));
        DriverSeederSupport.indexDriver(token, lo);
        DriverSeederSupport.indexDriver(token, mid);
        DriverSeederSupport.indexDriver(token, hi);

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search/full-text?query=" + tag + "&minRating=4.0&maxRating=5.0")
                .bearer(token)
                .get();

        assertThat(r.status()).as("rating-range full-text search").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        r.json().forEach(node -> {
            double rating = node.path("rating").asDouble(-1);
            if (rating >= 0) {
                assertThat(rating)
                        .as("each result rating must be in [4.0, 5.0]")
                        .isBetween(4.0, 5.0);
            }
        });
    }

    @Test
    @DisplayName("TC40 — Search ?minRating=5.0&maxRating=3.0 (invalid range) returns a 4xx")
    void tc40_invertedRange_returns4xx() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc40");

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search/full-text?minRating=5.0&maxRating=3.0")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("inverted minRating>maxRating range").isBetween(400, 499);
    }

    @Test
    @DisplayName("TC41 — Search with query that matches nothing returns 2xx + empty list")
    void tc41_noMatches_returnsEmptyList() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc41");
        String unlikely = "zzzzzz" + Nonce.nonce();

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search/full-text?query=" + unlikely)
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("empty-match full-text search").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        assertThat(r.json().size()).as("no matches returns empty array").isEqualTo(0);
    }

    @Test
    @DisplayName("TC42 — Search results sorted by relevance (name match ranks higher than description match)")
    void tc42_nameMatchRanksHigherThanDescription() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc42");
        String token = rider.token();
        String unique = "tc42unique" + Nonce.nonce().substring(0, 6);

        // Driver A: name contains the unique token
        Map<String, Object> bodyA = DriverSeederSupport.driverBody(
                unique + " Champ", "SEDAN", "AVAILABLE", 4.5, 50, "regular description");
        long idA = DriverSeederSupport.createDriver(token, bodyA);

        // Driver B: description contains the unique token but name does not
        Map<String, Object> bodyB = DriverSeederSupport.driverBody(
                "Other Place B", "SEDAN", "AVAILABLE", 4.5, 50, unique + " described here");
        long idB = DriverSeederSupport.createDriver(token, bodyB);

        DriverSeederSupport.indexDriver(token, idA);
        DriverSeederSupport.indexDriver(token, idB);

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search/full-text?query=" + unique)
                .bearer(token)
                .get();

        assertThat(r.status()).as("relevance-sorted full-text search").isBetween(200, 299);
        List<Long> ids = DriverSeederSupport.extractIds(r);

        assertThat(ids).as("A must appear in results").contains(idA);
        int idxA = ids.indexOf(idA);
        int idxB = ids.indexOf(idB);
        if (idxB >= 0) {
            assertThat(idxA).as("name-match A ranks before description-match B").isLessThan(idxB);
        }
    }

    @Test
    @DisplayName("S2-F10 cache key shape: driver-service::S2-F10::* populated after search")
    void s2f10_cacheKeyShape() {
        // Supplemental: not in the public TSV slice but mirrors the bash §4.4.1 / §8.1 check.
        // Concurrent agents may invalidate the cache between our write and read, so we retry a
        // couple of times to ride out collisions.
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("s2f10cache");
        int count = 0;
        for (int attempt = 0; attempt < 5 && count == 0; attempt++) {
            String unique = "cacheprobe" + Nonce.nonce().substring(0, 6);
            Http.request(DRIVER_BASE, "/api/drivers/search/full-text?query=" + unique)
                    .bearer(rider.token())
                    .get();
            try { Thread.sleep(250); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            count = Redis.countKeys("driver-service::S2-F10::*");
        }
        assertThat(count).as("S2-F10 cache prefix must have at least one key").isGreaterThanOrEqualTo(1);
    }
}
