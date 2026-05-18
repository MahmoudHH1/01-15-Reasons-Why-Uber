package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F4 — POST /api/payments/ride/{rideId} (TC305, 306, 307, 322, 325, 361, 367). */
@DisplayName("S5-F4 — Process payment for ride")
class PaymentProcessRideFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC305 — POST process creates Payment row for the ride")
    void tc305_processCreatesPaymentRow() {
        Seeders.Authed rider = Seeders.registerRider("tc305");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc305d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", rider.uid());
        body.put("amount", 50.0);
        body.put("method", "CREDIT_CARD");
        body.put("cardLastFour", "4242");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + ride).bearer(rider.token()).json(body).post();
        assertThat(r.status()).as("process 2xx").isBetween(200, 299);
        long pid = r.json().path("id").asLong();
        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("rideId").asLong()).isEqualTo(ride);
    }

    @Test
    @DisplayName("TC306 — Process for unknown rideId returns 404")
    void tc306_unknownRideReturns404() {
        Seeders.Authed rider = Seeders.registerRider("tc306");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/999999")
                .bearer(rider.token())
                .json(Map.of("userId", rider.uid(), "amount", 50.0, "method", "CASH"))
                .post();
        assertThat(r.status()).as("unknown ride").isEqualTo(404);
    }

    @Test
    @DisplayName("TC307 — Process when payment already exists returns 400")
    void tc307_duplicatePaymentReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc307");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc307d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 60.0);
        PaymentTestSupport.processPaymentForRide(rider.token(), rider.uid(), ride, 60.0, "CREDIT_CARD");
        // Second POST for same ride must reject
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + ride)
                .bearer(rider.token())
                .json(Map.of("userId", rider.uid(), "amount", 60.0, "method", "CASH"))
                .post();
        assertThat(r.status()).as("duplicate payment").isEqualTo(400);
    }

    @Test
    @DisplayName("TC322 — Process with method=BITCOIN returns 400")
    void tc322_invalidMethodReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc322");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc322d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + ride)
                .bearer(rider.token())
                .json(Map.of("userId", rider.uid(), "amount", 50.0, "method", "BITCOIN"))
                .post();
        assertThat(r.status()).as("invalid method").isEqualTo(400);
    }

    @Test
    @DisplayName("TC325 — Process with cardLastFour='12345' (length 5) returns 400")
    void tc325_invalidCardLengthReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc325");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc325d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", rider.uid());
        body.put("amount", 50.0);
        body.put("method", "CREDIT_CARD");
        body.put("cardLastFour", "12345");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + ride).bearer(rider.token()).json(body).post();
        assertThat(r.status()).as("invalid cardLastFour").isEqualTo(400);
    }

    @Test
    @DisplayName("TC361 — Process payment persists method=CASH to PG")
    void tc361_persistMethodCash() {
        Seeders.Authed rider = Seeders.registerRider("tc361");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc361d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 70.0);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + ride)
                .bearer(rider.token())
                .json(Map.of("userId", rider.uid(), "amount", 70.0, "method", "CASH"))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        long pid = r.json().path("id").asLong();
        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("method").asText()).isEqualTo("CASH");
    }

    @Test
    @DisplayName("TC367 — Process payment for CANCELLED ride returns 400")
    void tc367_cancelledRideReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc367");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc367d");
        long ride = PaymentTestSupport.seedCancelledRide(rider.token(), rider.uid(), drvId, 50.0);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + ride)
                .bearer(rider.token())
                .json(Map.of("userId", rider.uid(), "amount", 50.0, "method", "CREDIT_CARD"))
                .post();
        assertThat(r.status()).as("CANCELLED ride payment").isEqualTo(400);
    }
}
