package com.team01.uber.tests.crosscutting;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Seeders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting auth — role-claim forgery and role-gate enforcement.
 *
 * <p>The premise: the JWT signature must cover the entire payload. Modifying
 * the payload while keeping the original signature must invalidate the
 * token (rejected by the signature-validation handler with 401) or — at
 * worst — be caught by a server-side role re-check producing 403. Either
 * is spec-acceptable; what is forbidden is 2xx or 5xx.
 */
@DisplayName("Cross-cutting — role-claim forgery + role enforcement")
class RoleEnforcementFeatureTest extends BaseHttpTest {

    private static final String NON_USER_LIST_PATH = "/api/drivers";

    @Test
    @DisplayName("TC13 — Forged role-claim token (payload modified post-signing) is rejected")
    void tc13_forgedRoleClaim_isRejected() {
        // 1) Register a fresh RIDER and capture their real token.
        String realToken = Seeders.registerRider("tc13").token();

        // 2) Split on '.' — must have 3 parts (header.payload.signature).
        String[] parts = realToken.split("\\.");
        assertThat(parts).as("JWT must have 3 segments").hasSize(3);

        // 3) Base64url-decode the payload.
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

        // 4) Replace the role claim with ADMIN. If no role pattern is
        //    matched (e.g., the SUT spells the claim differently), inject
        //    an ADMIN claim by surgically appending it before the closing
        //    brace — this still produces a valid JSON object.
        String tamperedPayload;
        if (payloadJson.contains("\"role\":\"RIDER\"")) {
            tamperedPayload = payloadJson.replace("\"role\":\"RIDER\"", "\"role\":\"ADMIN\"");
        } else if (payloadJson.contains("\"role\":\"CUSTOMER\"")) {
            tamperedPayload = payloadJson.replace("\"role\":\"CUSTOMER\"", "\"role\":\"ADMIN\"");
        } else {
            int closing = payloadJson.lastIndexOf('}');
            assertThat(closing).as("payload JSON must end with '}'").isGreaterThan(0);
            tamperedPayload = payloadJson.substring(0, closing) + ",\"role\":\"ADMIN\"}";
        }

        // 5) Base64url-re-encode the tampered payload (no padding).
        String tamperedSegment = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayload.getBytes(StandardCharsets.UTF_8));

        // 6) Reassemble: original header, tampered payload, ORIGINAL signature.
        String forgedToken = parts[0] + "." + tamperedSegment + "." + parts[2];

        // 7) Hit a non-User CRUD list with the forged token.
        Http.Response response = Http.request(DRIVER_BASE, NON_USER_LIST_PATH)
                .bearer(forgedToken)
                .get();

        // 8) Must be neither 2xx (privilege-escalation succeeded) nor 5xx
        //    (filter crashed on tampered payload). Acceptable: 401 from
        //    signature check, or 403 if server re-validates role from DB.
        int status = response.status();
        assertThat(status >= 300 || status < 200)
                .as("forged ADMIN role-claim returned status %d — must NOT be 2xx "
                        + "(privilege escalation succeeded — critical security bug)", status)
                .isTrue();
        assertThat(status < 500 || status >= 600)
                .as("forged ADMIN role-claim returned status %d — must NOT be 5xx "
                        + "(filter crashed on tampered payload instead of cleanly rejecting)", status)
                .isTrue();
    }
}
