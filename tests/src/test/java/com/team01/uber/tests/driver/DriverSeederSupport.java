package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local helpers for driver-service feature tests. The shared {@code Seeders.seedDriver} fixture
 * does not include the {@code status} field that the driver entity requires, so this helper
 * builds the full body and lets each test customise fields as needed.
 *
 * <p>Mirrors the bash {@code create_driver} helper in {@code tests/20-driver-service.sh}.
 */
final class DriverSeederSupport {

    static final String USER_BASE   = BaseHttpTest.USER_BASE;
    static final String DRIVER_BASE = BaseHttpTest.DRIVER_BASE;
    static final String RIDE_BASE   = BaseHttpTest.RIDE_BASE;

    private DriverSeederSupport() {}

    record AuthedUser(long uid, String email, String token) {}

    /** Register a fresh rider and capture its bearer + uid. */
    static AuthedUser registerRider(String tag) {
        String email = Nonce.email(tag);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag + " User");
        body.put("email", email);
        body.put("password", "TestPwd!2026");
        body.put("phone", Nonce.phone());
        Http.Response r = Http.request(USER_BASE, "/api/auth/register").json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("registerRider(" + tag + ") failed: " + r.status() + " " + r.body());
        }
        String token = r.json().path("token").asText();
        return new AuthedUser(JwtClaims.uidOf(token), email, token);
    }

    /** Tries the DataSeeder admin first ({@code admin@uber.com}/{@code admin123}), then the fixture default. */
    static String adminTokenOrNull() {
        Http.Response r = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", "admin@uber.com", "password", "admin123"))
                .post();
        if (r.status() >= 200 && r.status() < 300) {
            return r.json().path("token").asText(null);
        }
        Http.Response alt = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", "admin@uber.io", "password", "Admin!2026"))
                .post();
        if (alt.status() >= 200 && alt.status() < 300) {
            return alt.json().path("token").asText(null);
        }
        return null;
    }

    /** Build a driver-create body with sensible defaults; caller may override fields via {@link Map#of(Object...)}. */
    static Map<String, Object> driverBody(String tag) {
        return driverBody(tag, "SEDAN", "AVAILABLE", 4.2, 10, "toyota camry");
    }

    static Map<String, Object> driverBody(String tag,
                                          String vehicleType,
                                          String status,
                                          double rating,
                                          int totalRatings,
                                          String description) {
        String salt = Nonce.nonce();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag);
        body.put("email", tag.replace(' ', '-').toLowerCase() + "-" + salt + "@x.io");
        body.put("phone", Nonce.phone());
        body.put("licenseNumber", "LIC-" + salt);
        body.put("status", status);
        body.put("rating", rating);
        body.put("totalRatings", totalRatings);
        body.put("createdAt", "2026-04-01T00:00:00");

        Map<String, Object> vehicleDetails = new LinkedHashMap<>();
        vehicleDetails.put("vehicleType", vehicleType);
        vehicleDetails.put("plate", "PL-" + salt.substring(0, Math.min(8, salt.length())));
        if (description != null) {
            vehicleDetails.put("description", description);
        }
        body.put("vehicleDetails", vehicleDetails);
        return body;
    }

    /** POST /api/drivers with the given body + token. Returns the new driver id, asserts 2xx. */
    static long createDriver(String token, Map<String, Object> body) {
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("createDriver failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Convenience: register a rider AND create a driver in one step. Returns (rider, driverId). */
    static SeededRiderDriver seedRiderAndDriver(String tag) {
        AuthedUser rider = registerRider(tag);
        long driverId = createDriver(rider.token(), driverBody(tag));
        return new SeededRiderDriver(rider, driverId);
    }

    static SeededRiderDriver seedRiderAndDriver(String tag, Map<String, Object> driverBody) {
        AuthedUser rider = registerRider(tag);
        long driverId = createDriver(rider.token(), driverBody);
        return new SeededRiderDriver(rider, driverId);
    }

    record SeededRiderDriver(AuthedUser rider, long driverId) {}

    /** Seed a ride directly via ride-service. Returns the ride id. */
    static long seedRide(String token, long userId, long driverId, double fare, String status) {
        return seedRide(token, userId, driverId, fare, status, "2026-03-15T10:00:00", "2026-03-15T10:30:00");
    }

    static long seedRide(String token, long userId, long driverId, double fare, String status,
                         String requestedAt, String completedAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("driverId", driverId);
        body.put("pickupLatitude", 30.0);
        body.put("pickupLongitude", 31.0);
        body.put("dropoffLatitude", 30.1);
        body.put("dropoffLongitude", 31.1);
        body.put("fare", fare);
        body.put("status", status);
        body.put("requestedAt", requestedAt);
        if (completedAt != null) body.put("completedAt", completedAt);
        body.put("metadata", Map.of());
        Http.Response r = Http.request(RIDE_BASE, "/api/rides").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedRide failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Convenience: returns the documentId of a freshly-seeded driver doc. */
    static long seedDocument(String token, long driverId, String type, String expiry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("documentUrl", "https://example.com/doc-" + Nonce.nonce() + ".pdf");
        body.put("expiryDate", expiry);
        body.put("verified", false);
        body.put("uploadedAt", "2026-04-01T00:00:00");
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/documents")
                .bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedDocument failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Index a driver synchronously and wait a beat for ES refresh. */
    static void indexDriver(String token, long driverId) {
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/index")
                .bearer(token).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("indexDriver failed: " + r.status() + " " + r.body());
        }
        try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Helper: extract a list of driver IDs from a full-text-search response array. */
    static List<Long> extractIds(Http.Response r) {
        java.util.ArrayList<Long> out = new java.util.ArrayList<>();
        r.json().forEach(node -> {
            long id = node.path("id").asLong(-1);
            if (id > 0) out.add(id);
        });
        return out;
    }
}
