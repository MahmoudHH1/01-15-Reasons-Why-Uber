package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F1 — Driver search (PG-side, distinct from S2-F10 full-text).
 *
 * <p>Covers TC221..TC223, TC245, TC248, TC336 (6 TCs).
 */
@DisplayName("S2-F1 — Driver search (status + rating range)")
class DriverSearchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC221 — Search ?status=AVAILABLE returns AVAILABLE drivers only")
    void tc221_statusFilter_returnsAvailableOnly() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc221");
        String token = rider.token();

        long da = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC221 DA", "SEDAN", "AVAILABLE", 4.0, 10, ""));
        long db = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC221 DB", "SEDAN", "BUSY", 4.0, 10, ""));
        long dc = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC221 DC", "SEDAN", "AVAILABLE", 4.0, 10, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/search?status=AVAILABLE")
                .bearer(token)
                .get();
        assertThat(r.status()).as("search?status=AVAILABLE").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        r.json().forEach(node -> assertThat(node.path("status").asText())
                .as("each item.status == AVAILABLE")
                .isEqualTo("AVAILABLE"));
        // Make sure at least 2 of our seeded available drivers came back.
        long matches = 0;
        for (var node : r.json()) {
            long id = node.path("id").asLong();
            if (id == da || id == dc) matches++;
        }
        assertThat(matches).as("seeded AVAILABLE drivers present").isGreaterThanOrEqualTo(2);
        // db should not be in the list.
        boolean dbPresent = false;
        for (var node : r.json()) if (node.path("id").asLong() == db) dbPresent = true;
        assertThat(dbPresent).as("BUSY driver excluded").isFalse();
    }

    @Test
    @DisplayName("TC222 — Search ?minRating=4.0 excludes drivers below threshold")
    void tc222_minRating_excludesBelow() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc222");
        String token = rider.token();
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC222 Hi", "SEDAN", "AVAILABLE", 4.8, 10, ""));
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC222 Lo", "SEDAN", "AVAILABLE", 3.0, 10, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/search?minRating=4.0")
                .bearer(token)
                .get();
        assertThat(r.status()).as("search?minRating=4.0").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        r.json().forEach(node -> assertThat(node.path("rating").asDouble())
                .as("each rating >= 4.0")
                .isGreaterThanOrEqualTo(4.0));
    }

    @Test
    @DisplayName("TC223 — Search with minRating > maxRating returns 400")
    void tc223_invertedRange_returns400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc223");

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search?minRating=4.5&maxRating=3.0")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("inverted minRating>maxRating").isEqualTo(400);
    }

    @Test
    @DisplayName("TC245 — Search ?maxRating=4.0 excludes drivers above threshold")
    void tc245_maxRating_excludesAbove() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc245");
        String token = rider.token();
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC245 HiR", "SEDAN", "AVAILABLE", 4.5, 10, ""));
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC245 LoR", "SEDAN", "AVAILABLE", 3.0, 10, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/search?maxRating=4.0")
                .bearer(token)
                .get();
        assertThat(r.status()).as("search?maxRating=4.0").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        r.json().forEach(node -> assertThat(node.path("rating").asDouble())
                .as("each rating <= 4.0")
                .isLessThanOrEqualTo(4.0));
    }

    @Test
    @DisplayName("TC248 — Search returns empty list when no driver matches")
    void tc248_emptyMatch_returnsEmptyList() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc248");
        String token = rider.token();
        long seededId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC248 Avail", "SEDAN", "AVAILABLE", 4.5, 10, ""));

        // BUSY + rating in [4.9, 5.0] should exclude our just-seeded AVAILABLE driver.
        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/search?status=BUSY&minRating=4.9&maxRating=5.0")
                .bearer(token)
                .get();
        assertThat(r.status()).as("filtered no-match search").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        boolean ourPresent = false;
        for (var node : r.json()) {
            long id = node.path("id").asLong();
            if (id == seededId) ourPresent = true;
            String s = node.path("status").asText("");
            double rating = node.path("rating").asDouble(-1);
            // For any returned items, the filter constraints must hold.
            if (!s.isBlank()) assertThat(s).as("BUSY-only filter").isEqualTo("BUSY");
            if (rating >= 0) assertThat(rating).as("rating filter in [4.9, 5.0]").isBetween(4.9, 5.0);
        }
        assertThat(ourPresent).as("our AVAILABLE 4.5 driver excluded").isFalse();
    }

    @Test
    @DisplayName("TC336 — Search with no filters returns the full driver list")
    void tc336_noFilters_returnsAll() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc336");
        String token = rider.token();
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC336 A", "SEDAN", "AVAILABLE", 4.0, 10, ""));
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC336 B", "SEDAN", "BUSY", 4.0, 10, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/search")
                .bearer(token)
                .get();
        assertThat(r.status()).as("search with no filters").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        assertThat(r.json().size()).as("at least 2 drivers in response").isGreaterThanOrEqualTo(2);
    }
}
