package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F6 — Revenue report (TC312, 313, 314, 363). */
@DisplayName("S5-F6 — Revenue report")
class PaymentRevenueFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC312 — Revenue report sums COMPLETED amounts in date range")
    void tc312_revenueSumsCompleted() {
        Seeders.Authed rider = Seeders.registerRider("tc312");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc312d");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 100.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 200.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/reports/revenue?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        double totalRevenue = r.json().path("totalRevenue").asDouble(r.json().path("total_revenue").asDouble(0));
        // Other tests contribute too — assert ≥ 300
        assertThat(totalRevenue).as("totalRevenue ≥ 300").isGreaterThanOrEqualTo(300.0);
    }

    @Test
    @DisplayName("TC313 — Revenue report exposes refundedAmount/refundCount")
    void tc313_refundFields() {
        Seeders.Authed rider = Seeders.registerRider("tc313");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc313d");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 100.0, "CREDIT_CARD");
        // Refund it so refundedAmount > 0
        Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund")
                .bearer(rider.token())
                .json(java.util.Map.of("reason", "x"))
                .put();
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/reports/revenue?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        assertThat(r.json().has("refundCount") || r.json().has("refund_count")).as("refundCount field").isTrue();
        assertThat(r.json().has("refundedAmount") || r.json().has("refunded_amount")).as("refundedAmount field").isTrue();
    }

    @Test
    @DisplayName("TC314 — Revenue with no payments in range returns totalRevenue=0")
    void tc314_emptyRangeReturnsZero() {
        Seeders.Authed rider = Seeders.registerRider("tc314");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/reports/revenue?startDate=2099-01-01&endDate=2099-01-31")
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        double totalRevenue = r.json().path("totalRevenue").asDouble(r.json().path("total_revenue").asDouble(-1));
        assertThat(totalRevenue).as("empty range totalRevenue == 0").isEqualTo(0.0);
    }

    @Test
    @DisplayName("TC363 — Revenue.averagePayment = totalRevenue / totalTransactions")
    void tc363_averagePayment() {
        Seeders.Authed rider = Seeders.registerRider("tc363");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc363d");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 100.0, "CREDIT_CARD");
        PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 50.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/reports/revenue?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        // averagePayment must equal totalRevenue / totalTransactions
        double total = r.json().path("totalRevenue").asDouble(r.json().path("total_revenue").asDouble(0));
        long txns = r.json().path("totalTransactions").asLong(r.json().path("total_transactions").asLong(0));
        double avg = r.json().path("averagePayment").asDouble(r.json().path("average_payment").asDouble(-1));
        if (txns > 0 && avg >= 0) {
            assertThat(avg).as("averagePayment formula")
                    .isCloseTo(total / txns, org.assertj.core.data.Offset.offset(0.5));
        } else {
            // averagePayment may be missing on some SUTs — assert field is present
            assertThat(r.json().has("averagePayment") || r.json().has("average_payment"))
                    .as("averagePayment field").isTrue();
        }
    }
}
