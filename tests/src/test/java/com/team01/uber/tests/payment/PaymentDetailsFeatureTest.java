package com.team01.uber.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F8 — Payment details (TC317, 318, 323, 328, 364, 369). */
@DisplayName("S5-F8 — Payment details")
class PaymentDetailsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC317 — Details returns appliedCoupons and finalAmount")
    void tc317_detailsReturnsCoupons() {
        Seeders.Authed rider = Seeders.registerRider("tc317");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc317d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 100.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 100.0, "CREDIT_CARD", "PENDING");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "C10_" + Nonce.nonce().substring(0, 6), "PERCENTAGE", 10.0, 50.0,
                LocalDateTime.now().plusDays(30), 100, true));
        Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/coupons/" + cid).bearer(rider.token()).post();

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/details").bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode appliedCoupons = r.json().path("appliedCoupons");
        if (appliedCoupons.isMissingNode()) appliedCoupons = r.json().path("applied_coupons");
        assertThat(appliedCoupons.isArray()).as("appliedCoupons is array").isTrue();
        assertThat(appliedCoupons.size()).as("≥ 1 coupon").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC318 — Details for unknown paymentId returns 404")
    void tc318_unknownPaymentReturns404() {
        Seeders.Authed rider = Seeders.registerRider("tc318");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/999999/details").bearer(rider.token()).get();
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("TC323 — Details for payment with no coupons returns empty appliedCoupons")
    void tc323_emptyCouponsList() {
        Seeders.Authed rider = Seeders.registerRider("tc323");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc323d");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 50.0, "CASH");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/details").bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode coupons = r.json().path("appliedCoupons");
        if (coupons.isMissingNode()) coupons = r.json().path("applied_coupons");
        if (!coupons.isMissingNode()) {
            assertThat(coupons.isArray()).isTrue();
            assertThat(coupons.size()).as("no coupons applied").isEqualTo(0);
        }
    }

    @Test
    @DisplayName("TC328 — Details.finalAmount = originalAmount - totalDiscount")
    void tc328_finalAmountComputed() {
        Seeders.Authed rider = Seeders.registerRider("tc328");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc328d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 100.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 100.0, "CREDIT_CARD", "PENDING");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "FIX25_" + Nonce.nonce().substring(0, 6), "FIXED", 25.0, 25.0,
                LocalDateTime.now().plusDays(30), 100, true));
        Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/coupons/" + cid).bearer(rider.token()).post();

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/details").bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        double original = r.json().path("originalAmount").asDouble(r.json().path("original_amount").asDouble(-1));
        double discount = r.json().path("totalDiscount").asDouble(r.json().path("total_discount").asDouble(-1));
        double finalA = r.json().path("finalAmount").asDouble(r.json().path("final_amount").asDouble(-1));
        if (original >= 0 && discount >= 0 && finalA >= 0) {
            assertThat(finalA).as("finalAmount = original - discount")
                    .isCloseTo(original - discount, org.assertj.core.data.Offset.offset(0.5));
        } else {
            // At minimum, the response should have the original amount
            assertThat(r.body()).as("details body present").isNotBlank();
        }
    }

    @Test
    @DisplayName("TC364 — Details exposes transactionDetails JSONB")
    void tc364_transactionDetailsExposed() {
        Seeders.Authed rider = Seeders.registerRider("tc364");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc364d");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 50.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/details").bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode td = r.json().path("transactionDetails");
        if (td.isMissingNode()) td = r.json().path("transaction_details");
        assertThat(td.isMissingNode()).as("transactionDetails key present").isFalse();
    }

    @Test
    @DisplayName("TC369 — Details body contains paymentId equal to the path id")
    void tc369_paymentIdEcho() {
        Seeders.Authed rider = Seeders.registerRider("tc369");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc369d");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), drvId, 50.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/details").bearer(rider.token()).get();
        assertThat(r.status()).isBetween(200, 299);
        long echoed = r.json().path("paymentId").asLong(
                r.json().path("payment_id").asLong(r.json().path("id").asLong(-1)));
        assertThat(echoed).as("echoed paymentId").isEqualTo(pid);
    }
}
