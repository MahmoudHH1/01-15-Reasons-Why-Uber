package com.team01.uber.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Eventually;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Redis;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5-F12 — Process Ride Refund with Surge Handling (TC175..TC190).
 *
 * <p>Validates the DP-1 Strategy implementation: FullRefundWithSurgeStrategy,
 * BaseFareOnlyRefundStrategy, and NoRefundStrategy. Same payment + different refundSurge
 * flag → different refundAmount.
 */
@DisplayName("S5-F12 — Refund-surge-adjusted (Strategy pattern)")
class PaymentRefundSurgeFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC175 — Fresh payment + refundSurge=true → FullRefundWithSurgeStrategy: refund=full amount")
    void tc175_fullRefundWithSurge() {
        Seeders.Authed rider = Seeders.registerRider("tc175");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc175", "SEDAN");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "driver_no_show", "refundSurge", true))
                .post();
        assertThat(r.status()).as("full refund 2xx").isBetween(200, 299);
        double refundAmount = r.json().path("transactionDetails").path("refundAmount").asDouble(-1);
        boolean refundSurgeIncluded = r.json().path("transactionDetails").path("refundSurgeIncluded").asBoolean(false);
        assertThat(refundAmount).as("refundAmount = full 200").isEqualTo(200.0);
        assertThat(refundSurgeIncluded).as("refundSurgeIncluded=true").isTrue();
    }

    @Test
    @DisplayName("TC176 — Fresh payment + refundSurge=false → BaseFareOnlyRefundStrategy: refund=amount-surgeFee")
    void tc176_baseFareOnlyRefund() {
        Seeders.Authed rider = Seeders.registerRider("tc176");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc176", "SUV");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");

        // Read the surgeFee the SUT computed (15% by default unless overridden)
        Http.Response paymentRead = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        double surgeFee = paymentRead.json().path("transactionDetails").path("surgeFee").asDouble(30.0);
        double amount = paymentRead.json().path("amount").asDouble(200.0);

        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "driver_no_show", "refundSurge", false))
                .post();
        assertThat(r.status()).as("base-only refund 2xx").isBetween(200, 299);
        double refundAmount = r.json().path("transactionDetails").path("refundAmount").asDouble(-1);
        JsonNode surgeIncludedNode = r.json().path("transactionDetails").path("refundSurgeIncluded");
        assertThat(refundAmount).as("refundAmount = amount - surgeFee")
                .isCloseTo(amount - surgeFee, org.assertj.core.data.Offset.offset(0.5));
        assertThat(surgeIncludedNode.asBoolean(true)).as("refundSurgeIncluded=false").isFalse();
    }

    @org.junit.jupiter.api.Disabled("DEFERRED: requires direct UPDATE on payments.created_at to age >24h; covered by bash test 50-payment-service.sh (g)")
    @Test
    @DisplayName("TC177 — Payment older than 24h → NoRefundStrategy → 400 'refund window expired'")
    void tc177_noRefundStrategy() { }

    @Test
    @DisplayName("TC178 — Refund attempt on PENDING payment returns 400")
    void tc178_pendingRefundReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc178");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc178d");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), rideId, 50.0, "CREDIT_CARD", "PENDING");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).as("PENDING refund → 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC179 — Refund attempt on FAILED payment returns 400")
    void tc179_failedRefundReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc179");
        long drvId = PaymentTestSupport.seedAvailableDriver(rider.token(), "tc179d");
        long rideId = PaymentTestSupport.seedCompletedRide(rider.token(), rider.uid(), drvId, 50.0);
        long pid = PaymentTestSupport.seedRawPayment(rider.token(), rider.uid(), rideId, 50.0, "CREDIT_CARD", "FAILED");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).as("FAILED refund → 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC180 — Refund attempt on already REFUNDED payment returns 400")
    void tc180_refundedRefundReturns400() {
        Seeders.Authed rider = Seeders.registerRider("tc180");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc180", "SEDAN");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        // First refund succeeds
        Http.Response first = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "first", "refundSurge", true))
                .post();
        assertThat(first.status()).as("first refund 2xx").isBetween(200, 299);
        // Second refund must be rejected
        Http.Response second = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "second", "refundSurge", true))
                .post();
        assertThat(second.status()).as("idempotency: second refund → 400").isEqualTo(400);
    }

    @Test
    @DisplayName("TC181 — Refund of non-existent payment returns 404")
    void tc181_refundUnknownReturns404() {
        Seeders.Authed rider = Seeders.registerRider("tc181");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/999999/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).as("unknown payment → 404").isEqualTo(404);
    }

    @Test
    @DisplayName("TC182 — Refund without Authorization header returns 401")
    void tc182_noAuthReturns401() {
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/1/refund-surge-adjusted")
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).as("no auth → 401").isEqualTo(401);
    }

    @Test
    @DisplayName("TC183 — Refund with malformed JWT returns 401")
    void tc183_malformedJwtReturns401() {
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/1/refund-surge-adjusted")
                .header("Authorization", "Bearer xxx.yyy.zzz")
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).as("malformed jwt → 401").isEqualTo(401);
    }

    @Test
    @DisplayName("TC184 — On successful refund, payment.status becomes REFUNDED")
    void tc184_statusBecomesRefunded() {
        Seeders.Authed rider = Seeders.registerRider("tc184");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc184", "SUV");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        // Read back the payment row
        Http.Response read = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        assertThat(read.json().path("status").asText()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("TC185 — On success, transactionDetails.refundAmount carries computed amount")
    void tc185_refundAmountComputed() {
        Seeders.Authed rider = Seeders.registerRider("tc185");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc185", "SEDAN");
        // amount=150, surgeFee default = 22.5 → base-only refund = 127.5
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 150.0, "CREDIT_CARD");
        Http.Response paymentRead = Http.request(PAYMENT_BASE, "/api/payments/" + pid).bearer(rider.token()).get();
        double surgeFee = paymentRead.json().path("transactionDetails").path("surgeFee").asDouble(22.5);
        double amount = paymentRead.json().path("amount").asDouble(150.0);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", false))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        double refundAmount = r.json().path("transactionDetails").path("refundAmount").asDouble(-1);
        assertThat(refundAmount).as("refundAmount = amount - surgeFee")
                .isCloseTo(amount - surgeFee, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("TC186 — transactionDetails has refundAmount, refundSurgeIncluded, refundReason, refundedAt")
    void tc186_jsonbKeysPresent() {
        Seeders.Authed rider = Seeders.registerRider("tc186");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc186", "SUV");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 200.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "driver_no_show", "refundSurge", true))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        JsonNode td = r.json().path("transactionDetails");
        assertThat(td.has("refundAmount")).as("refundAmount key").isTrue();
        assertThat(td.has("refundSurgeIncluded") || td.has("refundSurge")).as("refundSurgeIncluded key").isTrue();
        assertThat(td.has("refundReason") || td.has("reason")).as("refundReason key").isTrue();
        assertThat(td.has("refundedAt") || td.has("refunded_at")).as("refundedAt key").isTrue();
    }

    @Test
    @DisplayName("TC187 — On success, REFUNDED doc written to payment_audit_trail")
    void tc187_refundedAuditWritten() {
        Seeders.Authed rider = Seeders.registerRider("tc187");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc187", "SEDAN");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        Eventually.await(Duration.ofSeconds(8),
                () -> Mongo.count("payment_audit_trail",
                        Map.of("paymentId", pid, "action", "REFUNDED")) >= 1);
    }

    @org.junit.jupiter.api.Disabled("DEFERRED: requires direct UPDATE to backdate created_at >24h; covered by bash test 50-payment-service.sh (g)")
    @Test
    @DisplayName("TC188 — NoRefundStrategy denial path writes REFUND_DENIED to payment_audit_trail BEFORE 400")
    void tc188_refundDeniedAudit() { }

    @Test
    @DisplayName("TC189 — Successful refund removes payment-service::S5-F10::* keys from Redis")
    void tc189_refundEvictsS5F10Cache() {
        Seeders.Authed rider = Seeders.registerRider("tc189");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc189", "SEDAN");
        // Warm S5-F10 cache
        Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/vehicle-type?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        int beforeKeys = Redis.countKeys("payment-service::S5-F10::*");
        // Seed and refund
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        // Wait briefly then assert eviction
        Eventually.await(Duration.ofSeconds(5),
                () -> Redis.countKeys("payment-service::S5-F10::*") < Math.max(beforeKeys, 1));
    }

    @Test
    @DisplayName("TC190 — Successful refund removes payment-service::S5-F11::* keys from Redis")
    void tc190_refundEvictsS5F11Cache() {
        Seeders.Authed rider = Seeders.registerRider("tc190");
        PaymentTestSupport.TypedDriver d = PaymentTestSupport.seedTypedDriver(rider.token(), "tc190", "SUV");
        // Warm S5-F11 cache
        Http.request(PAYMENT_BASE,
                        "/api/payments/analytics/methods?startDate=" + PaymentTestSupport.windowStart()
                                + "&endDate=" + PaymentTestSupport.windowEnd())
                .bearer(rider.token()).get();
        int beforeKeys = Redis.countKeys("payment-service::S5-F11::*");
        long pid = PaymentTestSupport.seedPaidRide(rider.token(), rider.uid(), d.driverId(), 100.0, "CREDIT_CARD");
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/" + pid + "/refund-surge-adjusted")
                .bearer(rider.token())
                .json(Map.of("reason", "x", "refundSurge", true))
                .post();
        assertThat(r.status()).isBetween(200, 299);
        Eventually.await(Duration.ofSeconds(5),
                () -> Redis.countKeys("payment-service::S5-F11::*") < Math.max(beforeKeys, 1));
    }
}
