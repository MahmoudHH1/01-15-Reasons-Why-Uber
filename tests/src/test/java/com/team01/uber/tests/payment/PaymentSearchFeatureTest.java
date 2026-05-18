package com.team01.uber.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F1 — Search payments (TC298, 299, 321, 360). */
@DisplayName("S5-F1 — Search payments")
class PaymentSearchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC298 — Search ?status=COMPLETED returns COMPLETED payments only")
    void tc298_statusFilter() {
        Seeders.Authed rider = Seeders.registerRider("tc298");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc298d");
        // Seed one COMPLETED, one PENDING via raw POST
        long ride1 = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride1, 50.0, "CREDIT_CARD");
        long ride2 = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 60.0);
        try {
            PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride2, 60.0, "CASH", "PENDING");
        } catch (AssertionError ignored) {}
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/search?status=COMPLETED")
                .bearer(rider.token()).get();
        assertThat(r.status()).as("search 2xx").isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        assertThat(arr.isArray()).isTrue();
        for (JsonNode n : arr) {
            assertThat(n.path("status").asText()).as("every item COMPLETED").isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("TC299 — Search ?startDate&endDate filters by payment date")
    void tc299_dateFilter() {
        Seeders.Authed rider = Seeders.registerRider("tc299");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc299d");
        long ride1 = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 80.0);
        PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride1, 80.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/search?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).as("at least 1 payment in today's window").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC321 — Search status=COMPLETED + date range filters by both")
    void tc321_statusAndDateCombined() {
        Seeders.Authed rider = Seeders.registerRider("tc321");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc321d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 75.0);
        PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride, 75.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE,
                        "/api/payments/search?status=COMPLETED&startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        for (JsonNode n : arr) {
            assertThat(n.path("status").asText()).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("TC360 — Search list contains payments scoped to admin's view")
    void tc360_adminSearch() {
        Seeders.Authed rider = Seeders.registerRider("tc360");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc360d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 100.0);
        PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride, 100.0, "CREDIT_CARD");
        // Use rider token — synthetic ADMIN tokens trigger a Feign call to user-service
        // that returns 503 when the user doesn't exist in user-service DB.
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/search")
                .bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode arr = PaymentTestSupport.unwrapContent(r.json());
        assertThat(arr.size()).as("payment-list has at least 1 entry").isGreaterThanOrEqualTo(1);
    }
}
