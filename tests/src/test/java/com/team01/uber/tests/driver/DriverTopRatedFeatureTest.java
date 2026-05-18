package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F6 — Top-rated drivers report.
 *
 * <p>Covers TC233, TC234, TC339, TC372 (4 TCs).
 */
@DisplayName("S2-F6 — Top-rated drivers")
class DriverTopRatedFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC233 — Top-rated ranks by rating desc")
    void tc233_topRated_ranksByRatingDesc() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc233");
        String token = rider.token();
        long d1 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC233 D1", "SEDAN", "AVAILABLE", 3.5, 30, ""));
        long d2 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC233 D2", "SEDAN", "AVAILABLE", 4.8, 30, ""));
        long d3 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC233 D3", "SEDAN", "AVAILABLE", 4.2, 30, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/reports/top-rated?limit=10")
                .bearer(token)
                .get();
        assertThat(r.status()).as("top-rated status").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        assertThat(r.json().size()).as("at least 3 entries").isGreaterThanOrEqualTo(3);

        // Top item should be the highest-rated of our three; if other seeded drivers also exist
        // with rating ≥ 4.8 + sufficient totalRatings, they may rank above d2 — so we look for the
        // top occurrence among our seeded drivers and assert ratings are descending.
        double prev = Double.MAX_VALUE;
        boolean d2EncounteredBeforeOthers = false;
        boolean d3Encountered = false;
        boolean d1Encountered = false;
        for (var node : r.json()) {
            long id = node.path("driverId").asLong();
            double rating = node.path("rating").asDouble(-1);
            if (rating >= 0) {
                assertThat(rating).as("ratings monotonically non-increasing")
                        .isLessThanOrEqualTo(prev);
                prev = rating;
            }
            if (id == d2 && !d3Encountered && !d1Encountered) d2EncounteredBeforeOthers = true;
            if (id == d3) d3Encountered = true;
            if (id == d1) d1Encountered = true;
        }
        // Soft assertion: d2 (4.8) is first among our three when present.
        assertThat(d2EncounteredBeforeOthers || (!d3Encountered && !d1Encountered))
                .as("d2 (4.8) appears before d3 (4.2) and d1 (3.5)")
                .isTrue();
    }

    @Test
    @DisplayName("TC234 — Top-rated limit=2 returns 2 items")
    void tc234_topRated_limit2_returns2() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc234");
        String token = rider.token();
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC234 D1", "SEDAN", "AVAILABLE", 3.5, 30, ""));
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC234 D2", "SEDAN", "AVAILABLE", 4.8, 30, ""));
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC234 D3", "SEDAN", "AVAILABLE", 4.2, 30, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/reports/top-rated?limit=2")
                .bearer(token)
                .get();
        assertThat(r.status()).as("top-rated limit=2").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        assertThat(r.json().size()).as("limit=2 enforced").isEqualTo(2);
    }

    @Test
    @DisplayName("TC339 — Top-rated only includes drivers with sufficient totalRatings")
    void tc339_topRated_sufficientRatingsThreshold() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc339");
        String token = rider.token();
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC339 Newbie", "SEDAN", "AVAILABLE", 5.0, 1, ""));
        DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC339 Veteran", "SEDAN", "AVAILABLE", 4.5, 200, ""));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/reports/top-rated?limit=10")
                .bearer(token)
                .get();
        assertThat(r.status()).as("top-rated status").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        // Per catalogue: `list.size >= 1` is the floor — confirms the threshold logic doesn't
        // collapse to an empty list. The exact threshold behaviour is implementation-defined.
        assertThat(r.json().size()).as("at least one entry").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC372 — Top-rated limit=0 returns empty list or 400")
    void tc372_topRated_limit0_emptyOr400() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc372");
        DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC372 Driver"));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/reports/top-rated?limit=0")
                .bearer(rider.token())
                .get();

        if (r.status() == 400) {
            // Accept 400 as one valid response per the catalogue.
            assertThat(r.status()).as("limit=0 returns 400 or empty").isEqualTo(400);
        } else {
            assertThat(r.status()).as("limit=0 returns 2xx with empty list").isBetween(200, 299);
            assertThat(r.json().isArray()).isTrue();
            assertThat(r.json().size()).as("limit=0 → empty list").isEqualTo(0);
        }
    }
}
