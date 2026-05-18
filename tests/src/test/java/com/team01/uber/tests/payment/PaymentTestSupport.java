package com.team01.uber.tests.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Nonce;
import com.team01.uber.tests.fixtures.Seeders;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payment-specific seed helpers and small DTO/JSON utilities.
 *
 * <p>Seeders.seedDriver omits the required Driver.status field, so this class supplies
 * its own seedAvailableDriver / seedTypedDriver that includes status=AVAILABLE +
 * vehicleDetails JSONB. All emails / plates / license numbers use {@link Nonce}.
 *
 * <p>The SUT stamps requestedAt and createdAt server-side. Analytics queries against
 * a far-historical window will see empty results — use {@link #windowStart()} /
 * {@link #windowEnd()} for a wide window around today.
 */
final class PaymentTestSupport {

    static final String DRIVER_BASE  = Seeders.DRIVER_BASE;
    static final String RIDE_BASE    = Seeders.RIDE_BASE;
    static final String USER_BASE    = Seeders.USER_BASE;
    static final String PAYMENT_BASE = Seeders.PAYMENT_BASE;

    static String windowStart() {
        return LocalDateTime.now().minusDays(60).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    static String windowEnd() {
        return LocalDateTime.now().plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    record TypedDriver(long driverId) {}

    private PaymentTestSupport() {}

    /** Seed a driver with explicit status=AVAILABLE — Seeders.seedDriver lacks this required field. */
    static long seedAvailableDriver(String token, String tag) {
        return seedTypedDriver(token, tag, "STANDARD").driverId();
    }

    /** Seed a driver with the given vehicleType in vehicleDetails JSONB. */
    static TypedDriver seedTypedDriver(String token, String tag, String vehicleType) {
        String s = Nonce.nonce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag + " " + vehicleType);
        body.put("email", "vt_" + s + "@grader.testgen.io");
        body.put("phone", Nonce.phone());
        body.put("licenseNumber", "LIC-VT-" + s);
        body.put("rating", 4.0);
        body.put("totalRatings", 10);
        body.put("status", "AVAILABLE");
        body.put("createdAt", "2026-01-01T00:00:00");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("vehicleType", vehicleType);
        details.put("plate", "VT-" + s.substring(0, Math.min(8, s.length())).toUpperCase());
        body.put("vehicleDetails", details);
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedTypedDriver " + vehicleType + " failed: " + r.status() + " " + r.body());
        }
        return new TypedDriver(r.json().path("id").asLong());
    }

    /** Seed a COMPLETED ride for the given driver/rider; returns ride id. */
    static long seedCompletedRide(String token, long riderId, long driverId, double fare) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", riderId);
        body.put("driverId", driverId);
        body.put("pickupLatitude", 30.0);
        body.put("pickupLongitude", 31.0);
        body.put("dropoffLatitude", 30.1);
        body.put("dropoffLongitude", 31.1);
        body.put("status", "COMPLETED");
        body.put("fare", fare);
        body.put("metadata", Map.of("surgeMultiplier", 1.18));
        Http.Response r = Http.request(RIDE_BASE, "/api/rides").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedCompletedRide failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Seed a CANCELLED ride; returns ride id. */
    static long seedCancelledRide(String token, long riderId, long driverId, double fare) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", riderId);
        body.put("driverId", driverId);
        body.put("pickupLatitude", 30.0);
        body.put("pickupLongitude", 31.0);
        body.put("dropoffLatitude", 30.1);
        body.put("dropoffLongitude", 31.1);
        body.put("status", "CANCELLED");
        body.put("fare", fare);
        Http.Response r = Http.request(RIDE_BASE, "/api/rides").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedCancelledRide failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** POST /api/payments/ride/{rideId} happy-path → returns paymentId. */
    static long processPaymentForRide(String token, long riderId, long rideId, double amount, String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", riderId);
        body.put("amount", amount);
        body.put("method", method);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments/ride/" + rideId).bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("processPaymentForRide failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Direct POST /api/payments with explicit status — used for status-guard tests. */
    static long seedRawPayment(String token, long riderId, long rideId, double amount,
                               String method, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", riderId);
        body.put("rideId", rideId);
        body.put("amount", amount);
        body.put("method", method);
        body.put("status", status);
        Map<String, Object> td = new LinkedHashMap<>();
        td.put("surgeFee", amount * 0.15);
        body.put("transactionDetails", td);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedRawPayment[" + status + "] failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Convenience: driver + ride + payment chain. Returns paymentId. */
    static long seedPaidRide(String token, long riderId, long driverId, double amount, String method) {
        long rideId = seedCompletedRide(token, riderId, driverId, amount);
        return processPaymentForRide(token, riderId, rideId, amount, method);
    }

    /** Try the seeded admin first; fall back to a synthetic ADMIN-role JWT. */
    static String adminToken() {
        String real = Seeders.adminTokenOrNull();
        if (real != null) return real;
        return JwtTestHelper.tokenFor(999_001L, "admin@grader.testgen.io", "ADMIN");
    }

    /**
     * Build a coupon body matching the Coupon entity required fields:
     * code, discountType (PERCENTAGE|FIXED), discountValue, maxUses, expiryDate.
     */
    static Map<String, Object> coupon(String code, String type, double discount, double maxDiscount,
                                       LocalDateTime expiryDate, int maxUses, boolean active) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("discountType", type);
        body.put("discountValue", discount);
        body.put("maxUses", maxUses);
        body.put("expiryDate", expiryDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        body.put("currentUses", 0);
        body.put("active", active);
        return body;
    }

    static long seedCoupon(String token, Map<String, Object> couponBody) {
        Http.Response r = Http.request(PAYMENT_BASE, "/api/coupons").bearer(token).json(couponBody).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedCoupon failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    static JsonNode findRowByVehicleType(JsonNode array, String vehicleType) {
        if (array == null || !array.isArray()) return null;
        for (JsonNode n : array) {
            if (vehicleType.equals(n.path("vehicleType").asText(null))) return n;
        }
        return null;
    }

    static JsonNode findRowByMethod(JsonNode array, String method) {
        if (array == null || !array.isArray()) return null;
        for (JsonNode n : array) {
            if (method.equals(n.path("method").asText(null))) return n;
        }
        return null;
    }

    static JsonNode unwrapContent(JsonNode body) {
        if (body == null) return null;
        if (body.has("content") && body.get("content").isArray()) return body.get("content");
        return body;
    }
}
