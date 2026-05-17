package com.team01.uber.tests.fixtures;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One-stop multi-service seeders for tests that need an existing user → driver → ride → payment chain.
 *
 * <p>Each method hits the real service on its docker-compose port and returns a small record with
 * the IDs / tokens needed for follow-up assertions. Uses {@link Nonce} for uniqueness so repeated
 * runs don't collide on email / phone / license / plate uniques.
 */
public final class Seeders {

    public static final String USER_BASE     = System.getProperty("service.user.base",     "http://localhost:8081");
    public static final String DRIVER_BASE   = System.getProperty("service.driver.base",   "http://localhost:8082");
    public static final String RIDE_BASE     = System.getProperty("service.ride.base",     "http://localhost:8083");
    public static final String LOCATION_BASE = System.getProperty("service.location.base", "http://localhost:8084");
    public static final String PAYMENT_BASE  = System.getProperty("service.payment.base",  "http://localhost:8085");

    public record Authed(long uid, String email, String token) {}
    public record SeededDriver(long driverId, String token) {}
    public record SeededRide(long rideId) {}
    public record SeededPayment(long paymentId) {}

    private Seeders() {}

    public static Authed registerRider(String tag) {
        String email = Nonce.email(tag);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag + " User");
        body.put("email", email);
        body.put("password", "TestPwd!2026");
        body.put("phone", Nonce.phone());
        Http.Response r = Http.request(USER_BASE, "/api/auth/register").json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("registerRider " + tag + " failed: " + r.status() + " " + r.body());
        }
        String token = r.json().path("token").asText();
        return new Authed(JwtClaims.uidOf(token), email, token);
    }

    /**
     * Try to log in as ADMIN with the default seeded admin credentials documented in the bash scripts
     * ({@code admin@uber.io} / {@code Admin!2026}). Returns the admin token if successful.
     * Falls back to {@code null} when the SUT has no admin seed — callers should skip admin-only TCs.
     */
    public static String adminTokenOrNull() {
        Http.Response r = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", "admin@uber.io", "password", "Admin!2026"))
                .post();
        if (r.status() < 200 || r.status() >= 300) return null;
        return r.json().path("token").asText(null);
    }

    public static SeededDriver seedDriver(String token, String tag) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag + " Driver");
        body.put("email", Nonce.email(tag + "drv"));
        body.put("phone", Nonce.phone());
        body.put("licenseNumber", "LIC-" + Nonce.nonce());
        body.put("vehiclePlate", "PL-" + Nonce.nonce().substring(0, 6).toUpperCase());
        body.put("vehicleType", "STANDARD");
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedDriver failed: " + r.status() + " " + r.body());
        }
        long id = r.json().path("id").asLong();
        return new SeededDriver(id, token);
    }

    public static SeededRide seedRide(String token, long riderId, long driverId, double fare) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", riderId);
        body.put("driverId", driverId);
        body.put("fare", fare);
        body.put("pickupLat", 30.0444);
        body.put("pickupLng", 31.2357);
        body.put("dropoffLat", 30.0561);
        body.put("dropoffLng", 31.2394);
        Http.Response r = Http.request(RIDE_BASE, "/api/rides").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedRide failed: " + r.status() + " " + r.body());
        }
        return new SeededRide(r.json().path("id").asLong());
    }

    public static SeededPayment seedPayment(String token, long rideId, double amount, String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rideId", rideId);
        body.put("amount", amount);
        body.put("method", method);
        Http.Response r = Http.request(PAYMENT_BASE, "/api/payments").bearer(token).json(body).post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedPayment failed: " + r.status() + " " + r.body());
        }
        return new SeededPayment(r.json().path("id").asLong());
    }

    /** Read a JSON path from a service for assertions. */
    public static JsonNode getJson(String base, String path, String token) {
        Http.Builder b = Http.request(base, path);
        if (token != null) b = b.bearer(token);
        return b.get().json();
    }
}
