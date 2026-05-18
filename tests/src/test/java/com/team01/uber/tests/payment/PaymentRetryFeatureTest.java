package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F7 — Retry FAILED payments (TC315, 316, 368). */
@DisplayName("S5-F7 — Retry failed payment")
class PaymentRetryFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC315 — PUT retry on FAILED payment flips status")
    void tc315_retryFailedFlipsStatus() {
        Seeders.Authed rider = Seeders.registerRider("tc315");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc315d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 50.0, "CREDIT_CARD", "FAILED");

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/retry").bearer(rider.token()).put();
        assertThat(r.status()).as("retry 2xx").isBetween(200, 299);
        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("status").asText()).as("status no longer FAILED").isNotEqualTo("FAILED");
    }

    @Test
    @DisplayName("TC316 — Retry COMPLETED payment returns 400")
    void tc316_retryCompletedReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc316");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc316d");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 50.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/retry").bearer(rider.token()).put();
        assertThat(r.status()).as("retry COMPLETED 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC368 — Retry on FAILED payment is permitted to update transactionDetails")
    void tc368_retryFailedAllowed() {
        Seeders.Authed rider = Seeders.registerRider("tc368");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc368d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 50.0, "CREDIT_CARD", "FAILED");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/retry").bearer(rider.token()).put();
        assertThat(r.status()).isBetween(200, 299);
        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("status").asText()).isNotEqualTo("FAILED");
    }
}
