package com.team01.uber.tests.crosscutting;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting auth — negative paths on a non-User CRUD list endpoint.
 *
 * <p>All TCs use {@code GET /api/drivers} as the canonical "first top-level
 * non-User entity" so the auth-filter behaviour proven here generalises
 * beyond the user-service slice (whose negative paths are covered in
 * {@link com.team01.uber.tests.user.JwtValidationFeatureTest}).
 */
@DisplayName("Cross-cutting — JWT negative paths on a non-User CRUD list")
class NonUserCrudAuthFeatureTest extends BaseHttpTest {

    private static final String NON_USER_LIST_PATH = "/api/drivers";

    @Test
    @DisplayName("TC07 — Missing Authorization header on a non-User CRUD list returns 401")
    void tc07_missingAuthHeader_returns401() {
        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH).get();

        // Assertion ladder per catalogue: not 2xx, not 5xx, not 404, not 403, strictly 401.
        // The strict equality at the end implies all the negatives, but we order
        // assertions by severity so the failure message points at the worst bug first.
        assertNotWideOpen(response.status());
        assertNotCrash(response.status());
        assertThat(response.status())
                .as("non-User CRUD without Authorization — must not be 404 (auth never enforced)")
                .isNotEqualTo(404);
        assertThat(response.status())
                .as("non-User CRUD without Authorization — must not be 403 (no creds → 401, not 403)")
                .isNotEqualTo(403);
        assertThat(response.status())
                .as("non-User CRUD without Authorization — must be strictly 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC08 — Tampered JWT signature is rejected with 401")
    void tc08_tamperedSignatureOnNonUserCrud_returns401() {
        String validToken = Seeders.registerRider("tc08cc").token();
        String[] parts = validToken.split("\\.");
        // Preserve header + payload (so structure check passes), corrupt the signature segment.
        String tampered = parts[0] + "." + parts[1] + "."
                + parts[2].substring(0, Math.max(0, parts[2].length() - 4)) + "XXXX";

        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH)
                .bearer(tampered)
                .get();

        assertNotWideOpen(response.status());
        assertNotCrash(response.status());
        assertThat(response.status())
                .as("tampered signature on non-User CRUD — must not be 404")
                .isNotEqualTo(404);
        assertThat(response.status())
                .as("tampered signature on non-User CRUD — must not be 403 (forged → 401, not 403)")
                .isNotEqualTo(403);
        assertThat(response.status())
                .as("tampered signature on non-User CRUD — must be strictly 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC10 — Empty Bearer token returns 401")
    void tc10_emptyBearerOnNonUserCrud_returns401() {
        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH)
                .header("Authorization", "Bearer ")
                .get();

        assertNotWideOpen(response.status());
        assertNotCrash(response.status());
        assertThat(response.status())
                .as("empty Bearer on non-User CRUD — must be strictly 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC11 — Non-Bearer scheme (Basic) returns 401")
    void tc11_basicSchemeOnNonUserCrud_returns401() {
        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH)
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .get();

        assertNotWideOpen(response.status());
        assertNotCrash(response.status());
        assertThat(response.status())
                .as("Basic scheme on non-User CRUD — must be strictly 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("TC12 — Garbage non-JWT token returns 401")
    void tc12_garbageTokenOnNonUserCrud_returns401() {
        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH)
                .header("Authorization", "Bearer not_a_valid_jwt")
                .get();

        assertNotWideOpen(response.status());
        assertNotCrash(response.status());
        assertThat(response.status())
                .as("garbage non-JWT on non-User CRUD — must be strictly 401")
                .isEqualTo(401);
    }

    // -- helpers ----------------------------------------------------------

    private static void assertNotWideOpen(int status) {
        // status must not be in [200, 299]. Use the boolean route since
        // AssertJ's AbstractIntegerAssert has no isNotBetween.
        assertThat(status >= 300 || status < 200)
                .as("status %d is in 2xx — endpoint is wide-open (critical security bug)", status)
                .isTrue();
    }

    private static void assertNotCrash(int status) {
        // status must not be in [500, 599] — that would mean the filter crashed
        // rather than cleanly rejecting.
        assertThat(status < 500 || status >= 600)
                .as("status %d is 5xx — filter chain crashed instead of cleanly rejecting", status)
                .isTrue();
    }
}
