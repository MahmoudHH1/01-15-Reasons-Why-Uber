package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F2 — M1 refund (PUT /api/payments/{id}/refund). TC300, 301, 302, 324, 366. */
@DisplayName("S5-F2 — M1 refund (PUT /refund)")
class PaymentRefundFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC300 — PUT refund flips payment status to REFUNDED")
    void tc300_refundFlipsStatus() {
        Seeders.Authed rider = Seeders.registerRider("tc300");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc300d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 60.0);
        long pid = PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride, 60.0, "CREDIT_CARD");

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund")
                .bearer(rider.token())
                .json(Map.of("reason", "Customer request"))
                .put();
        assertThat(r.status()).as("refund 2xx").isBetween(200, 299);

        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("TC301 — Refund a PENDING payment returns 400")
    void tc301_pendingRefundReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc301");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc301d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 30.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 30.0, "CREDIT_CARD", "PENDING");

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund")
                .bearer(rider.token())
                .json(Map.of("reason", "x"))
                .put();
        assertThat(r.status()).as("PENDING refund 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC302 — Refund unknown paymentId returns 404")
    void tc302_unknownRefundReturns404() {
        Seeders.Authed rider = Seeders.registerRider("tc302");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/999999/refund")
                .bearer(rider.token())
                .json(Map.of("reason", "x"))
                .put();
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("TC324 — Refund of unknown payment returns 404")
    void tc324_unknownRefundReturns404Again() {
        Seeders.Authed rider = Seeders.registerRider("tc324");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/999999/refund")
                .bearer(rider.token())
                .json(Map.of("reason", "x"))
                .put();
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("TC366 — Refund a REFUNDED payment returns 400")
    void tc366_doubleRefundReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc366");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc366d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 60.0);
        long pid = PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride, 60.0, "CREDIT_CARD");

        // First refund — succeeds
        Http.Response first = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund")
                .bearer(rider.token()).json(Map.of("reason", "first")).put();
        assertThat(first.status()).isBetween(200, 299);

        // Second refund — must reject
        Http.Response second = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund")
                .bearer(rider.token()).json(Map.of("reason", "second")).put();
        assertThat(second.status()).as("second refund 400").isEqualTo(400);
    }
}
