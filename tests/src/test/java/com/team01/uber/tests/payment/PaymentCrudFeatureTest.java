package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S5 — Payment CRUD baseline (regression)")
class PaymentCrudFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("POST /api/payments creates a row; GET /api/payments/{id} echoes the same id")
    void crud_createAndRead() {
        Seeders.Authed rider = Seeders.registerRider("paycrud_r");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "paycrud_d");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 100.0);
        long pid = PaymentTestSupport.processPaymentForRide(
                rider.token(), rider.uid(), rideId, 100.0, "CREDIT_CARD");

        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.status()).as("GET payment by id").isBetween(200, 299);
        assertThat(read.json().path("id").asLong()).isEqualTo(pid);
    }

    @Test
    @DisplayName("PUT /api/payments/{id} updates fields persistently")
    void crud_update() {
        Seeders.Authed rider = Seeders.registerRider("payupd_r");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "payupd_d");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.processPaymentForRide(
                rider.token(), rider.uid(), rideId, 50.0, "CREDIT_CARD");

        Http.Response upd = Http.request(PAYMENT_BASE, "/api/payments/" + pid)
                .bearer(rider.token())
                .json(java.util.Map.of(
                        "userId", rider.uid(),
                        "rideId", rideId,
                        "amount", 55.5,
                        "method", "CREDIT_CARD",
                        "status", "COMPLETED"))
                .put();
        assertThat(upd.status()).as("PUT payment").isBetween(200, 299);

        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("amount").asDouble()).isEqualTo(55.5);
    }

    @Test
    @DisplayName("DELETE /api/payments/{id} removes the row; subsequent GET returns 404")
    void crud_delete() {
        Seeders.Authed rider = Seeders.registerRider("paydel_r");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "paydel_d");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 25.0);
        long pid = PaymentTestSupport.processPaymentForRide(
                rider.token(), rider.uid(), rideId, 25.0, "CREDIT_CARD");

        Http.Response del = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).delete();
        assertThat(del.status()).as("DELETE payment").isBetween(200, 299);

        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.status()).as("GET after delete").isEqualTo(404);
    }

    @Test
    @DisplayName("GET /api/payments without auth returns 401")
    void crud_unauth() {
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments").get();
        assertThat(r.status()).as("GET /api/payments without auth").isEqualTo(401);
    }
}
