package com.team01.uber.tests.payment;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S5-F5 — Apply coupon to payment (TC308, 309, 310, 311, 327, 362). */
@DisplayName("S5-F5 — Apply coupon to payment")
class PaymentCouponsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC308 — POST apply-coupon adds PaymentCoupon row")
    void tc308_applyCouponAddsRow() {
        Seeders.Authed rider = Seeders.registerRider("tc308");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc308d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 100.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 100.0, "CREDIT_CARD", "PENDING");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "PCT10_" + Nonce.nonce().substring(0, 6), "PERCENTAGE", 10.0, 50.0,
                LocalDateTime.now().plusDays(30), 100, true));
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/coupons/" + cid)
                .bearer(rider.token()).post();
        assertThat(r.status()).as("apply coupon 2xx").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC309 — Applying an expired coupon returns 400")
    void tc309_expiredCoupon() {
        Seeders.Authed rider = Seeders.registerRider("tc309");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc309d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 50.0, "CREDIT_CARD", "PENDING");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "EXP_" + Nonce.nonce().substring(0, 6), "PERCENTAGE", 10.0, 50.0,
                LocalDateTime.now().minusDays(10), 100, true));
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/coupons/" + cid)
                .bearer(rider.token()).post();
        assertThat(r.status()).as("expired coupon 400").isEqualTo(400);
    }

    @org.junit.jupiter.api.Disabled("DEFERRED: requires JDBC UPDATE to set currentUses=1; covered by bash tests")
    @Test
    @DisplayName("TC310 — Applying coupon whose currentUses==maxUses returns 400")
    void tc310_couponExhausted() { }

    @Test
    @DisplayName("TC311 — Applying inactive coupon returns 400")
    void tc311_inactiveCoupon() {
        Seeders.Authed rider = Seeders.registerRider("tc311");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc311d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 50.0, "CREDIT_CARD", "PENDING");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "INACT_" + Nonce.nonce().substring(0, 6), "PERCENTAGE", 10.0, 50.0,
                LocalDateTime.now().plusDays(30), 100, /*active=*/false));
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/coupons/" + cid)
                .bearer(rider.token()).post();
        assertThat(r.status()).as("inactive coupon 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC327 — apply-coupon with unknown paymentId returns 404")
    void tc327_unknownPaymentReturns404() {
        Seeders.Authed rider = Seeders.registerRider("tc327");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "UNK_" + Nonce.nonce().substring(0, 6), "PERCENTAGE", 10.0, 50.0,
                LocalDateTime.now().plusDays(30), 100, true));
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/999999/coupons/" + cid)
                .bearer(rider.token()).post();
        assertThat(r.status()).as("unknown payment").isEqualTo(404);
    }

    @Test
    @DisplayName("TC362 — Applying a coupon increments its currentUses")
    void tc362_currentUsesIncremented() {
        Seeders.Authed rider = Seeders.registerRider("tc362");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc362d");
        long ride = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), ride, 50.0, "CREDIT_CARD", "PENDING");
        long cid = PaymentTestSupport.seedCoupon(rider.token(), PaymentTestSupport.coupon(
                "USES_" + Nonce.nonce().substring(0, 6), "PERCENTAGE", 10.0, 50.0,
                LocalDateTime.now().plusDays(30), 100, true));
        Http.Response apply = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/coupons/" + cid)
                .bearer(rider.token()).post();
        assertThat(apply.status()).isBetween(200, 299);
        Http.Response read = Http.request(PAYMENT_BASE, "/api/coupons/" + cid).bearer(rider.token()).get();
        assertThat(read.json().path("currentUses").asInt(read.json().path("current_uses").asInt(0)))
                .as("currentUses incremented").isEqualTo(1);
    }
}
