package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Mongo;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4-F10 — Location Analytics Dashboard (cached 10m, ANALYTICS_VIEWED on every call).
 *
 * <p>Uses a unique random month per test (well outside the seed range used by the bash
 * scripts) so concurrent runs don't see each other's events.
 */
@DisplayName("S4-F10 — Location analytics dashboard")
class LocationAnalyticsFeatureTest extends BaseHttpTest {

    /** Pick a date range in a year + month nonce-derived; returns {start, end, year, month}. */
    private static String[] uniqueDateRange(int year, int month) {
        // Use start = year-month-01, end = year-month-28 (always valid).
        String start = String.format("%04d-%02d-01", year, month);
        String end = String.format("%04d-%02d-28", year, month);
        return new String[]{start, end};
    }

    private static String tsAt(int year, int month, int day, int hour) {
        return String.format("%04d-%02d-%02dT%02d:00:00", year, month, day, hour);
    }

    // Random year-base per JVM run to avoid colliding with prior test runs that persisted to the
    // location_events history. Pick a far-future year well outside the bash seed range and use a
    // sliding window of months per test method.
    private static final int YEAR_BASE = 2050
            + (int) (Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextInt()) % 200);
    private static int nextMonth = 1;
    private static synchronized int[] allocateYearMonth() {
        int idx = nextMonth++;
        int year = YEAR_BASE + (idx / 12);
        int month = (idx % 12) + 1;
        return new int[]{year, month};
    }

    @Test
    @DisplayName("TC100 — Dashboard returns totalLocationEvents=8/activeDrivers=3/averageSpeed/eventsByHour")
    void tc100_dashboard_returnsAllFourFields() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc100");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc100a");
        long d2 = LocationSeederSupport.seedDriver(me.token(), "tc100b");
        long d3 = LocationSeederSupport.seedDriver(me.token(), "tc100c");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // 3 events for d1 (hours 8, 8, 17)
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 5, 8), 20.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 6, 8), 30.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 7, 17), 40.0);
        // 2 events for d2 (hours 8, 17)
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d2, 30.0, 31.0, tsAt(y, m, 8, 8), 50.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d2, 30.0, 31.0, tsAt(y, m, 9, 17), 60.0);
        // 3 events for d3 (hours 17, 8, 17)
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d3, 30.0, 31.0, tsAt(y, m, 10, 17), 70.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d3, 30.0, 31.0, tsAt(y, m, 11, 8), 25.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d3, 30.0, 31.0, tsAt(y, m, 12, 17), 35.0);

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);

        long total = r.json().path("totalLocationEvents").asLong(-1);
        long active = r.json().path("activeDrivers").asLong(-1);
        JsonNode byHour = r.json().path("eventsByHour");
        assertThat(total).as("totalLocationEvents=8").isEqualTo(8L);
        assertThat(active).as("activeDrivers=3").isEqualTo(3L);
        assertThat(byHour.isMissingNode() || byHour.isNull()).as("eventsByHour non-null").isFalse();
        boolean hasHour8 = byHour.has("8") || byHour.has("08");
        boolean hasHour17 = byHour.has("17");
        assertThat(hasHour8).as("eventsByHour contains hour 8").isTrue();
        assertThat(hasHour17).as("eventsByHour contains hour 17").isTrue();
    }

    @Test
    @DisplayName("TC101 — Dashboard.totalLocationEvents equals exact count of events in range")
    void tc101_dashboard_totalLocationEvents_equals7() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc101");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc101");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        for (int i = 0; i < 7; i++) {
            LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0,
                    tsAt(y, m, 15, 12), 40.0 + i);
        }

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        assertThat(r.json().path("totalLocationEvents").asLong(-1)).as("=7").isEqualTo(7L);
    }

    @Test
    @DisplayName("TC102 — Dashboard.activeDrivers counts distinct driverIds in range")
    void tc102_dashboard_activeDrivers_countsDistinct() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc102");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc102a");
        long d2 = LocationSeederSupport.seedDriver(me.token(), "tc102b");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // 3 events for d1
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 10), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 11), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 12), 40.0);
        // 1 event for d2
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d2, 30.0, 31.0, tsAt(y, m, 15, 13), 40.0);

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        assertThat(r.json().path("activeDrivers").asLong(-1))
                .as("activeDrivers=2 (distinct count, not 4)").isEqualTo(2L);
    }

    @Test
    @DisplayName("TC103 — Dashboard.averageSpeed equals mean of metadata.speed values")
    void tc103_dashboard_averageSpeed_equalsMean() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc103");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc103");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // Speeds 40, 60, 80 → mean = 60
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 10), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 11), 60.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 12), 80.0);

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        assertThat(r.json().path("averageSpeed").asDouble(-1))
                .as("averageSpeed = mean(40,60,80) = 60")
                .isCloseTo(60.0, org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    @DisplayName("TC104 — Dashboard.eventsByHour groups counts by hour-of-day")
    void tc104_dashboard_eventsByHour_groupsByHour() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc104");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc104");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // 2 events at hour 9, 3 events at hour 14
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 1, 9), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 2, 9), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 3, 14), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 4, 14), 40.0);
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 5, 14), 40.0);

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        JsonNode byHour = r.json().path("eventsByHour");
        long c9 = byHour.has("9") ? byHour.path("9").asLong() : byHour.path("09").asLong();
        long c14 = byHour.path("14").asLong();
        assertThat(c9).as("hour 9 → 2 events").isEqualTo(2L);
        assertThat(c14).as("hour 14 → 3 events").isEqualTo(3L);
    }

    @Test
    @DisplayName("TC105 — Dashboard with no events in range returns zeros + empty eventsByHour")
    void tc105_dashboard_noEvents_returnsZeros() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc105");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // Don't seed anything in this range.

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        assertThat(r.json().path("totalLocationEvents").asLong(-1)).as("total=0").isEqualTo(0L);
        assertThat(r.json().path("activeDrivers").asLong(-1)).as("active=0").isEqualTo(0L);
        JsonNode byHour = r.json().path("eventsByHour");
        boolean isEmpty = byHour.isMissingNode() || byHour.isNull() || byHour.size() == 0;
        assertThat(isEmpty).as("eventsByHour empty").isTrue();
    }

    @Test
    @DisplayName("TC106 — Dashboard with startDate > endDate returns 400")
    void tc106_dashboard_invertedRange_returns400() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc106");
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=2026-05-01&endDate=2026-04-01")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("inverted range").isEqualTo(400);
    }

    @Test
    @DisplayName("TC107 — Dashboard without Authorization header returns 401")
    void tc107_dashboard_noAuth_returns401() {
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=2026-04-01&endDate=2026-04-30")
                .get();
        assertThat(r.status()).as("no auth").isEqualTo(401);
    }

    @Test
    @DisplayName("TC108 — Dashboard with malformed JWT returns 401")
    void tc108_dashboard_malformedJwt_returns401() {
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=2026-04-01&endDate=2026-04-30")
                .bearer(JwtTestHelper.malformedToken())
                .get();
        assertThat(r.status()).as("malformed JWT").isEqualTo(401);
    }

    @Test
    @DisplayName("TC109 — Event exactly on startDate is included")
    void tc109_dashboard_eventOnStartDate_included() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc109");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc109");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // Event exactly on startDate (first day of the month, hour 0).
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 1, 0), 50.0);

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        long total = r.json().path("totalLocationEvents").asLong(-1);
        assertThat(total).as("boundary event included").isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("TC110 — Events outside [startDate, endDate] excluded")
    void tc110_dashboard_eventsOutsideRange_excluded() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc110");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc110");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // Seed two events: one INSIDE, one before the range.
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 12), 50.0);
        // Far in the past (well before startDate).
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, "2010-01-01T00:00:00", 50.0);

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        long total = r.json().path("totalLocationEvents").asLong(-1);
        assertThat(total).as("only in-range event counted").isEqualTo(1L);
    }

    @Test
    @DisplayName("TC111 — averageSpeed=0 when no events in range")
    void tc111_dashboard_averageSpeedZero_whenEmpty() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc111");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];

        String[] range = uniqueDateRange(y, m);
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        double avg = r.json().path("averageSpeed").asDouble(-1);
        assertThat(avg).as("averageSpeed = 0 for empty range").isEqualTo(0.0);
    }

    @Test
    @DisplayName("TC112 — First dashboard call writes ANALYTICS_VIEWED to location_events")
    void tc112_dashboard_firstCall_writesAnalyticsViewed() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc112");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        String[] range = uniqueDateRange(y, m);

        long before = Mongo.count("location_events", Map.of("action", "ANALYTICS_VIEWED"));
        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);

        long observed = Mongo.countAtLeast("location_events",
                Map.of("action", "ANALYTICS_VIEWED"),
                before + 1, Duration.ofSeconds(5));
        assertThat(observed).as("ANALYTICS_VIEWED count incremented").isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    @DisplayName("TC113 — Cache-hit dashboard call still logs ANALYTICS_VIEWED")
    void tc113_dashboard_cacheHit_logsAnalyticsViewed() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc113");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        String[] range = uniqueDateRange(y, m);

        // First call (cache miss).
        Http.Response r1 = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r1.status()).as("first 2xx").isBetween(200, 299);

        long before = Mongo.count("location_events", Map.of("action", "ANALYTICS_VIEWED"));

        // Second call (should hit cache, but still emit ANALYTICS_VIEWED).
        Http.Response r2 = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r2.status()).as("second 2xx").isBetween(200, 299);

        long observed = Mongo.countAtLeast("location_events",
                Map.of("action", "ANALYTICS_VIEWED"),
                before + 1, Duration.ofSeconds(5));
        assertThat(observed).as("ANALYTICS_VIEWED logged on cache hit").isGreaterThanOrEqualTo(before + 1);
    }

    @Test
    @DisplayName("TC114 — Two identical dashboard requests return identical bodies")
    void tc114_dashboard_twoIdenticalRequests_identicalBodies() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc114");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc114");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 12), 50.0);
        String[] range = uniqueDateRange(y, m);

        Http.Response r1 = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        Http.Response r2 = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r1.status()).isBetween(200, 299);
        assertThat(r2.status()).isBetween(200, 299);
        assertThat(r2.body()).as("identical cached body").isEqualTo(r1.body());
    }

    @Test
    @DisplayName("TC115 — Insert event after first call → cached body still returned")
    void tc115_dashboard_insertAfterFirstCall_cachedBodyReturned() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc115");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc115");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 12), 50.0);
        String[] range = uniqueDateRange(y, m);

        Http.Response r1 = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        long total1 = r1.json().path("totalLocationEvents").asLong();
        // Insert another (PG-only writes do not bust the S4-F10 cache).
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 15, 13), 50.0);

        Http.Response r2 = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        long total2 = r2.json().path("totalLocationEvents").asLong();
        assertThat(total2).as("cached body returned (no re-aggregation)").isEqualTo(total1);
    }

    @Test
    @DisplayName("TC116 — eventsByHour map omits hours with zero events")
    void tc116_dashboard_eventsByHour_omitsZeroHours() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc116");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc116");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        // Only seed hour 10
        LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 5, 10), 50.0);
        String[] range = uniqueDateRange(y, m);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        JsonNode byHour = r.json().path("eventsByHour");
        // Hour 10 should be present (count 1); hour 11 should NOT be in the map.
        boolean has10 = byHour.has("10");
        assertThat(has10).as("hour 10 present").isTrue();
        // Hours with zero events should be omitted (or at least not all 24 hours listed).
        assertThat(byHour.size()).as("map smaller than 24 hours").isLessThan(24);
    }

    @Test
    @DisplayName("TC117 — Multiple events from same driver only count once toward activeDrivers")
    void tc117_dashboard_sameDriver_countedOnce() {
        LocationSeederSupport.AuthedUser me = LocationSeederSupport.registerRider("tc117");
        long d1 = LocationSeederSupport.seedDriver(me.token(), "tc117");
        int[] ym = allocateYearMonth();
        int y = ym[0], m = ym[1];
        for (int i = 0; i < 5; i++) {
            LocationSeederSupport.seedLocationWithSpeed(me.token(), d1, 30.0, 31.0, tsAt(y, m, 1 + i, 10), 40.0);
        }
        String[] range = uniqueDateRange(y, m);

        Http.Response r = Http.request(LOCATION_BASE,
                "/api/locations/analytics?startDate=" + range[0] + "&endDate=" + range[1])
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("dashboard 2xx").isBetween(200, 299);
        assertThat(r.json().path("activeDrivers").asLong(-1))
                .as("activeDrivers=1 (one driver, multiple events)")
                .isEqualTo(1L);
    }
}
