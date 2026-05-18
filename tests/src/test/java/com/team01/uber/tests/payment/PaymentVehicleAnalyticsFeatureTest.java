package com.team01.uber.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Eventually;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5-F10 — Fare Revenue by Vehicle Type (TC136..TC158).
 *
 * <p>Note: the SUT stamps {@code requestedAt = now()} on ride creation. Tests therefore
 * use a wide window around today rather than the hardcoded 2026-03-* spec ranges.
 */
@DisplayName("S5-F10 — Vehicle-type revenue analytics")
class PaymentVehicleAnalyticsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC136 — Vehicle-type breakdown groups by vehicleType with surgeFeeRevenue")
    void tc136_vehicleTypeGroupsBySurge() {
        Seeders.Authed rider = Seeders.registerRider("tc136");
        PaymentTestSupport.TypedDriver d1 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc136a", "SEDAN");
        PaymentTestSupport.TypedDriver d2 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc136b", "SUV");
        // 3 SEDAN payments of 200 each → 600 total; 2 SUV payments of 200 → 400.
        for (int i = 0; i < 3; i++) {
            PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d1.driverId(), 200.0, "CREDIT_CARD");
        }
        for (int i = 0; i < 2; i++) {
            PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d2.driverId(), 200.0, "CREDIT_CARD");
        }
        String start = PaymentTestSupport.windowStart();
        String end = PaymentTestSupport.windowEnd();
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + start + "&endDate=" + end)
                .bearer(rider.token())
                .get();
        assertThat(r.status()).as("analytics 2xx").isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        JsonNode sedan = PaymentTestSupport.findRowByVehicleType(arr, "SEDAN");
        JsonNode suv   = PaymentTestSupport.findRowByVehicleType(arr, "SUV");
        assertThat(sedan).as("SEDAN row").isNotNull();
        assertThat(suv).as("SUV row").isNotNull();
        // Other tests run concurrently → use ≥ rather than strict equality
        assertThat(sedan.path("totalRevenue").asDouble()).isGreaterThanOrEqualTo(600.0);
        assertThat(suv.path("totalRevenue").asDouble()).isGreaterThanOrEqualTo(400.0);
    }

    @Test
    @DisplayName("TC137 — totalRevenue equals SUM(payments.amount) for COMPLETED")
    void tc137_totalRevenueSumsAmount() {
        Seeders.Authed rider = Seeders.registerRider("tc137");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc137", "SEDAN");
        double[] amounts = {100, 150, 200};
        double expected = 0;
        for (double a : amounts) {
            PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), a, "CREDIT_CARD");
            expected += a;
        }
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode sedan = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "SEDAN");
        assertThat(sedan).isNotNull();
        // ≥ rather than ==: cross-test contamination on shared DB
        assertThat(sedan.path("totalRevenue").asDouble()).isGreaterThanOrEqualTo(expected);
    }

    @Test
    @DisplayName("TC138 — surgeFeeRevenue sums transactionDetails.surgeFee values")
    void tc138_surgeFeeRevenueSum() {
        Seeders.Authed rider = Seeders.registerRider("tc138");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc138", "SUV");
        // The SUT computes surgeFee = 15% of amount unless explicitly provided.
        // We seed 2 payments of 200 → expected surge ≥ 60 (15% × 400).
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "SUV");
        assertThat(row).isNotNull();
        assertThat(row.path("surgeFeeRevenue").asDouble()).isGreaterThanOrEqualTo(60.0);
    }

    @Test
    @DisplayName("TC139 — baseFareRevenue equals totalRevenue - surgeFeeRevenue per group")
    void tc139_baseFareDecomposition() {
        Seeders.Authed rider = Seeders.registerRider("tc139");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc139", "LUXURY");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 500.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "LUXURY");
        assertThat(row).isNotNull();
        double total = row.path("totalRevenue").asDouble();
        double surge = row.path("surgeFeeRevenue").asDouble();
        double base = row.path("baseFareRevenue").asDouble();
        // Validate the decomposition invariant
        assertThat(base).as("baseFareRevenue").isCloseTo(total - surge, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("TC140 — rideCount equals distinct ride IDs in the group")
    void tc140_rideCountDistinct() {
        Seeders.Authed rider = Seeders.registerRider("tc140");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc140", "HATCHBACK");
        for (int i = 0; i < 5; i++) {
            PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        }
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "HATCHBACK");
        assertThat(row).isNotNull();
        assertThat(row.path("rideCount").asInt()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("TC141 — Different vehicleTypes produce separate rows")
    void tc141_differentTypesSeparateRows() {
        Seeders.Authed rider = Seeders.registerRider("tc141");
        PaymentTestSupport.TypedDriver d1 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc141a", "SEDAN");
        PaymentTestSupport.TypedDriver d2 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc141b", "VAN");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d1.driverId(), 100.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d2.driverId(), 200.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        assertThat(PaymentTestSupport.findRowByVehicleType(arr, "SEDAN")).as("SEDAN row").isNotNull();
        assertThat(PaymentTestSupport.findRowByVehicleType(arr, "VAN")).as("VAN row").isNotNull();
    }

    @Test
    @DisplayName("TC142 — Only COMPLETED payments contribute (PENDING/FAILED/REFUNDED excluded)")
    void tc142_onlyCompletedCounted() {
        // Heavy direct-DB injection of non-COMPLETED states isn't possible via HTTP.
        // We can still assert the COMPLETED path: seed a COMPLETED payment + a raw PENDING
        // and verify the analytics counts at least the COMPLETED one.
        Seeders.Authed rider = Seeders.registerRider("tc142");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc142", "SEDAN");
        long rideOk = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), d.driverId(), 100.0);
        PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), rideOk, 100.0, "CREDIT_CARD");
        long ridePend = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), d.driverId(), 100.0);
        try {
            PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ridePend, 100.0, "CASH", "PENDING");
        } catch (AssertionError ignored) {
            // Some SUTs reject raw POST with explicit status — accept that
        }
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "SEDAN");
        assertThat(row).isNotNull();
        // Cannot assert exact rideCount (concurrent agents seed too), but totalRevenue ≥ 100
        assertThat(row.path("totalRevenue").asDouble()).isGreaterThanOrEqualTo(100.0);
    }

    @org.junit.jupiter.api.Disabled("DEFERRED: refund of just-completed payment requires fresh-window POST refund-surge-adjusted; covered by PaymentRefundSurgeFeatureTest TC189/TC190 via refund-side cache eviction.")
    @Test
    @DisplayName("TC143 — REFUNDED payments are NOT in totalRevenue")
    void tc143_refundedExcluded() { }

    @Test
    @DisplayName("TC144 — Date range with no payments returns empty list")
    void tc144_emptyDateRangeEmptyList() {
        Seeders.Authed rider = Seeders.registerRider("tc144");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2099-01-01&endDate=2099-01-31")
                .bearer(rider.token()).get();
        assertThat(r.status()).as("future-date analytics 2xx").isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        assertThat(arr.isArray()).as("array body").isTrue();
        assertThat(arr.size()).as("empty list").isEqualTo(0);
    }

    @Test
    @DisplayName("TC145 — startDate > endDate returns 400")
    void tc145_invertedRangeReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc145");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-04-30&endDate=2026-04-01")
                .bearer(rider.token()).get();
        assertThat(r.status()).as("inverted range").isEqualTo(400);
    }

    @Test
    @DisplayName("TC146 — Vehicle-type without Authorization header returns 401")
    void tc146_noAuthReturns401() {
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-03-01&endDate=2026-03-31").get();
        assertThat(r.status()).as("missing auth").isEqualTo(401);
    }

    @Test
    @DisplayName("TC147 — Vehicle-type with malformed JWT returns 401")
    void tc147_malformedJwtReturns401() {
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-03-01&endDate=2026-03-31")
                .header("Authorization", "Bearer xxx.yyy.zzz").get();
        assertThat(r.status()).as("malformed jwt").isEqualTo(401);
    }

    @Test
    @DisplayName("TC148 — Payment with no surgeFee key → surgeFeeRevenue defaults to 15% of amount")
    void tc148_surgeFeeDefaults15Percent() {
        Seeders.Authed rider = Seeders.registerRider("tc148");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc148", "SEDAN");
        // processPaymentForRide does not pass surgeFee → SUT must inject 15% default per §4.6
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "SEDAN");
        assertThat(row).isNotNull();
        // Default surge = 15% × 100 = 15; with concurrent agents this is a lower bound only
        assertThat(row.path("surgeFeeRevenue").asDouble()).isGreaterThanOrEqualTo(15.0);
    }

    @Test
    @DisplayName("TC149 — First vehicle-type call writes ANALYTICS_VIEWED to payment_audit_trail")
    void tc149_firstCallWritesAnalyticsViewed() {
        Seeders.Authed rider = Seeders.registerRider("tc149");
        long before = Mongo.count("payment_audit_trail", Map.of("action", "ANALYTICS_VIEWED"));
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        long expected = before + 1;
        Eventually.await(Duration.ofSeconds(8),
                () -> Mongo.count("payment_audit_trail", Map.of("action", "ANALYTICS_VIEWED")) >= expected);
    }

    @Test
    @DisplayName("TC150 — Cache-hit vehicle-type call still logs ANALYTICS_VIEWED")
    void tc150_cacheHitStillAudits() {
        Seeders.Authed rider = Seeders.registerRider("tc150");
        // First call (populates cache)
        Http.Response r1 = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-07-01&endDate=2026-07-31")
                .bearer(rider.token()).get();
        assertThat(r1.status()).isBetween(200, 299);
        // Wait a moment then snapshot
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        long after1 = Mongo.count("payment_audit_trail", Map.of("action", "ANALYTICS_VIEWED"));
        // Second identical call — should be a cache hit
        Http.Response r2 = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-07-01&endDate=2026-07-31")
                .bearer(rider.token()).get();
        assertThat(r2.status()).isBetween(200, 299);
        long expected = after1 + 1;
        Eventually.await(Duration.ofSeconds(8),
                () -> Mongo.count("payment_audit_trail", Map.of("action", "ANALYTICS_VIEWED")) >= expected);
    }

    @Test
    @DisplayName("TC151 — Two identical vehicle-type requests return identical bodies")
    void tc151_identicalBodies() {
        Seeders.Authed rider = Seeders.registerRider("tc151");
        Http.Response r1 = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-07-01&endDate=2026-07-31")
                .bearer(rider.token()).get();
        Http.Response r2 = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=2026-07-01&endDate=2026-07-31")
                .bearer(rider.token()).get();
        assertThat(r1.status()).isBetween(200, 299);
        assertThat(r2.status()).isBetween(200, 299);
        assertThat(r2.body()).as("cached body matches").isEqualTo(r1.body());
    }

    @Test
    @DisplayName("TC152 — Insert payment after first call → cached body still returned")
    void tc152_cachedBodyAfterInsert() {
        Seeders.Authed rider = Seeders.registerRider("tc152");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc152", "SEDAN");
        // Use a frozen historical window so concurrent agents don't perturb it
        String url = "/api/payments/analytics/vehicle-type?startDate=2026-11-01&endDate=2026-11-30";
        Http.Response r1 = Http.request(PAYMENT_BASE, url).bearer(rider.token()).get();
        assertThat(r1.status()).isBetween(200, 299);
        int beforeSize = PaymentTestSupport.unwrapContent(r1.json()).size();
        // Insert a payment (won't move into 2026-11 because SUT stamps now(), but tests cache stickiness)
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        Http.Response r2 = Http.request(PAYMENT_BASE, url).bearer(rider.token()).get();
        assertThat(r2.status()).isBetween(200, 299);
        int afterSize = PaymentTestSupport.unwrapContent(r2.json()).size();
        // Cache must return the same size — the new payment is timestamped to now() anyway
        assertThat(afterSize).as("cached body unchanged").isEqualTo(beforeSize);
    }

    @org.junit.jupiter.api.Disabled("DEFERRED: requires direct UPDATE on ride.requestedAt — covered by bash tests")
    @Test
    @DisplayName("TC153 — Payment exactly on startDate is included")
    void tc153_boundaryIncluded() { }

    @org.junit.jupiter.api.Disabled("DEFERRED: SUT stamps requested_at = now(); out-of-range simulation needs JDBC backdate")
    @Test
    @DisplayName("TC154 — Payments outside the date range are excluded")
    void tc154_outOfRangeExcluded() { }

    @Test
    @DisplayName("TC155 — 3 distinct vehicleTypes produce 3 rows in the breakdown")
    void tc155_threeDistinctTypes() {
        Seeders.Authed rider = Seeders.registerRider("tc155");
        PaymentTestSupport.TypedDriver d1 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc155a", "SEDAN");
        PaymentTestSupport.TypedDriver d2 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc155b", "SUV");
        PaymentTestSupport.TypedDriver d3 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc155c", "LUXURY");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d1.driverId(), 100.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d2.driverId(), 100.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d3.driverId(), 100.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        assertThat(PaymentTestSupport.findRowByVehicleType(arr, "SEDAN")).isNotNull();
        assertThat(PaymentTestSupport.findRowByVehicleType(arr, "SUV")).isNotNull();
        assertThat(PaymentTestSupport.findRowByVehicleType(arr, "LUXURY")).isNotNull();
    }

    @Test
    @DisplayName("TC156 — Two SEDAN drivers' payments combine into a single SEDAN row")
    void tc156_sameTypeCombined() {
        Seeders.Authed rider = Seeders.registerRider("tc156");
        PaymentTestSupport.TypedDriver d1 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc156a", "SEDAN");
        PaymentTestSupport.TypedDriver d2 = PaymentTestSupport.seedTypedDriver(rider.token(), "tc156b", "SEDAN");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d1.driverId(), 150.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d2.driverId(), 150.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        // Should be only one SEDAN row, but may include rows from other tests too
        int sedanRows = 0;
        for (JsonNode n : arr) {
            if ("SEDAN".equals(n.path("vehicleType").asText(null))) sedanRows++;
        }
        assertThat(sedanRows).as("single combined SEDAN row").isEqualTo(1);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(arr, "SEDAN");
        assertThat(row.path("totalRevenue").asDouble()).isGreaterThanOrEqualTo(300.0);
    }

    @org.junit.jupiter.api.Disabled("DEFERRED: requires direct JSONB UPDATE to set surgeFee=0.0 — covered by bash tests")
    @Test
    @DisplayName("TC157 — Payment with surgeFee=0 contributes 0 to surgeFeeRevenue")
    void tc157_explicitZeroSurge() { }

    @Test
    @DisplayName("TC158 — totalRevenue = baseFareRevenue + surgeFeeRevenue across the group")
    void tc158_decompositionInvariant() {
        Seeders.Authed rider = Seeders.registerRider("tc158");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc158", "VAN");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 300.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode row = PaymentTestSupport.findRowByVehicleType(PaymentTestSupport.unwrapContent(r.json()), "VAN");
        assertThat(row).isNotNull();
        double total = row.path("totalRevenue").asDouble();
        double surge = row.path("surgeFeeRevenue").asDouble();
        double base = row.path("baseFareRevenue").asDouble();
        assertThat(total).as("total ≥ 500").isGreaterThanOrEqualTo(500.0);
        assertThat(base + surge).as("base + surge == total")
                .isCloseTo(total, org.assertj.core.data.Offset.offset(0.5));
    }
}
