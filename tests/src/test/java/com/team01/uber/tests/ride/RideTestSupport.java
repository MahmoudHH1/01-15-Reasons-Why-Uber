package com.team01.uber.tests.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;
import com.team01.uber.tests.fixtures.Seeders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared helpers for ride-service feature tests.
 *
 * <p>Reuses {@link Seeders#registerRider} for auth, but provides ride-specific
 * creation helpers that take the full PG-schema payload (M3 has no Feign-checked
 * fields on POST /api/rides — it's a plain JPA save, then publish).
 */
final class RideTestSupport {

    static final String RIDE_BASE     = System.getProperty("service.ride.base",   "http://localhost:8083");
    static final String DRIVER_BASE   = System.getProperty("service.driver.base", "http://localhost:8082");
    static final String USER_BASE     = System.getProperty("service.user.base",  "http://localhost:8081");

    static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private RideTestSupport() {}

    record AuthedRider(long uid, String email, String token) {}

    static AuthedRider registerRider(String tag) {
        Seeders.Authed a = Seeders.registerRider(tag);
        return new AuthedRider(a.uid(), a.email(), a.token());
    }

    /** Seed a brand-new driver via driver-service. Returns the driver id. */
    static long seedDriver(String token, String tag) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", tag + " Drv");
        body.put("email", Nonce.email(tag + "_drv"));
        body.put("phone", Nonce.phone());
        body.put("licenseNumber", "LIC-" + Nonce.nonce());
        body.put("vehiclePlate", "PL-" + Nonce.nonce().substring(0, 6).toUpperCase());
        body.put("vehicleType", "STANDARD");
        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers")
                .bearer(token)
                .json(body)
                .post();
        if (r.status() < 200 || r.status() >= 300) {
            throw new AssertionError("seedDriver " + tag + " failed: " + r.status() + " " + r.body());
        }
        return r.json().path("id").asLong();
    }

    /** Body for POST /api/rides — accepts arbitrary status + fare overrides. */
    static Map<String, Object> rideBody(long userId, Long driverId, String status, Double fare,
                                        Map<String, Object> metadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        if (driverId != null) body.put("driverId", driverId);
        body.put("pickupLatitude", 30.0);
        body.put("pickupLongitude", 31.0);
        body.put("dropoffLatitude", 30.1);
        body.put("dropoffLongitude", 31.1);
        if (status != null) body.put("status", status);
        if (fare != null) body.put("fare", fare);
        if (metadata != null) body.put("metadata", metadata);
        body.put("requestedAt", LocalDateTime.now().format(ISO_LOCAL));
        body.put("completedAt", LocalDateTime.now().format(ISO_LOCAL));
        return body;
    }

    /** Create a ride and return its id. Defaults: COMPLETED status, fare=100, no metadata. */
    static long createRide(String token, long userId, Long driverId, String status, Double fare,
                           Map<String, Object> metadata) {
        Http.Response r = Http.request(RIDE_BASE, "/api/rides")
                .bearer(token)
                .json(rideBody(userId, driverId, status, fare, metadata))
                .post();
        assertThat(r.status())
                .as("POST /api/rides for status=" + status + " — expected 2xx, got %s body=%s",
                        r.status(), r.body())
                .isBetween(200, 299);
        return r.json().path("id").asLong();
    }

    /** Create a ride with sane defaults (COMPLETED, fare=100). */
    static long createCompletedRide(String token, long userId, Long driverId) {
        return createRide(token, userId, driverId, "COMPLETED", 100.0, Map.of());
    }

    /** Create a ride with sane defaults (REQUESTED, no fare). */
    static long createRequestedRide(String token, long userId, Long driverId) {
        return createRide(token, userId, driverId, "REQUESTED", null, Map.of());
    }

    /** Force a ride into IN_PROGRESS via PUT /api/rides/{id}. Returns the updated body. */
    static Http.Response putRide(String token, long rideId, Map<String, Object> body) {
        return Http.request(RIDE_BASE, "/api/rides/" + rideId)
                .bearer(token)
                .json(body)
                .put();
    }

    static JsonNode getRide(String token, long rideId) {
        return Http.request(RIDE_BASE, "/api/rides/" + rideId).bearer(token).get().json();
    }

    static String formatDate(LocalDate d) {
        return d.toString();
    }
}
