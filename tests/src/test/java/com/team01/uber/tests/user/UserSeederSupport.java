package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local helpers for the user-service feature tests. Kept in this folder so the shared {@link
 * com.team01.uber.tests.fixtures.Seeders} fixture stays unchanged — the documented admin
 * credentials there ({@code admin@uber.io / Admin!2026}) don't match the {@code DataSeeder}
 * that actually runs in the user-service ({@code admin@uber.com / admin123}). This helper
 * tries both so the tests survive future fixture fixes.
 */
final class UserSeederSupport {

    static final String USER_BASE = BaseHttpTest.USER_BASE;

    private UserSeederSupport() {}

    record AuthedUser(long uid, String email, String token) {}

    /** Tries the user-service {@code DataSeeder} credentials first, then the (currently wrong) Seeders default. */
    static String adminToken() {
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

    static AuthedUser registerRider(String tag) {
        return registerRider(tag, tag + " User");
    }

    static AuthedUser registerRider(String tag, String displayName) {
        String email = Nonce.email(tag);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", displayName);
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
}
