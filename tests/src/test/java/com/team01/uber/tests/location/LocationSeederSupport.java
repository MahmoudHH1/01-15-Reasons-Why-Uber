package com.team01.uber.tests.location;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local helpers for the location-service feature tests. Provides driver + location seeders
 * that align with the bash 40-location-service.sh flow.
 */
final class LocationSeederSupport {

    static final String USER_BASE = BaseHttpTest.USER_BASE;
    static final String DRIVER_BASE = BaseHttpTest.DRIVER_BASE;
    static final String LOCATION_BASE = BaseHttpTest.LOCATION_BASE;

    private LocationSeederSupport() {}

    record AuthedUser(long uid, String email, String token) {}

    /** Registers a fresh rider and returns the token. */
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

    /**
     * Seed a driver via driver-service POST /api/drivers and return its DB id.
     * Mirrors the bash seed_loc_driver flow.
     */
    static long seedDriver(String token, String tag) {
        String salt = Nonce.nonce().replaceAll("[^a-zA-Z0-9]", "").substring(0, 12);
        Map<String, Object> vehicle = new LinkedHashMap<>();
        vehicle.put("vehicleType", "SEDAN");
        vehicle.put("plate", "PL-" + salt.toUpperCase());
        vehicle.put("description", "");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag + " Driver");
        body.put("email", "loc-" + salt + "@grader.testgen.io");
        body.put("phone", Nonce.phone());
        body.put("licenseNumber", "LIC-LOC-" + salt);
        body.put("rating", 4.0);
        body.put("totalRatings", 0);
        body.put("status", "AVAILABLE");
        body.put("createdAt", "2026-04-01T00:00:00");
        body.put("vehicleDetails", vehicle);

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers")
                .bearer(token)
                .json(body)
                .post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedDriver(" + tag + ") failed: " + r.status() + " " + r.body());
        }
        long id = r.json().path("id").asLong();
        if (id <= 0) {
            throw new AssertionError("seedDriver(" + tag + ") returned no id: " + r.body());
        }
        return id;
    }

    /**
     * Create a Location row directly via POST /api/locations with full payload.
     * Returns the persisted location id.
     */
    static long seedLocation(String token, long driverId, double lat, double lon,
                             String timestamp, Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("driverId", driverId);
        body.put("latitude", lat);
        body.put("longitude", lon);
        body.put("timestamp", timestamp);
        if (metadata != null) body.put("metadata", metadata);

        Http.Response r = Http.request(LOCATION_BASE, "/api/locations")
                .bearer(token)
                .json(body)
                .post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedLocation failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Convenience seed with speed-only metadata. */
    static long seedLocationWithSpeed(String token, long driverId, double lat, double lon,
                                      String timestamp, double speed) {
        return seedLocation(token, driverId, lat, lon, timestamp, Map.of("speed", speed));
    }

    /** Convenience: timestamp = now formatted as LocalDateTime. */
    static String nowTs() {
        return LocalDateTime.now().withNano(0).toString();
    }

    /** Convenience: post a tracking event via POST /api/locations/{driverId}/tracking. */
    static Http.Response postTracking(String token, long driverId, Map<String, Object> body) {
        return Http.request(LOCATION_BASE, "/api/locations/" + driverId + "/tracking")
                .bearer(token)
                .json(body)
                .post();
    }

    /** Convenience: GET /api/locations/{driverId}/tracking (with optional query). */
    static Http.Response getTimeline(String token, long driverId, String query) {
        String path = "/api/locations/" + driverId + "/tracking" + (query == null ? "" : query);
        return Http.request(LOCATION_BASE, path).bearer(token).get();
    }

    /** Best-effort delete; ignore failures. */
    static void deleteDriver(String token, long driverId) {
        try {
            Http.request(DRIVER_BASE, "/api/drivers/" + driverId).bearer(token).delete();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }
}
