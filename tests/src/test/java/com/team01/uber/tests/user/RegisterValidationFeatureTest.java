package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F10/F11 — Register + login validation")
class RegisterValidationFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC14 — Register with missing required field returns 4xx (negative path)")
    void tc14_missingEmail_returns4xx() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "TC14 User");
        // intentionally no email
        body.put("password", "TestPwd!2026");
        body.put("phone", Nonce.phone());

        Http.Response r = Http.request(USER_BASE, "/api/auth/register").json(body).post();

        assertThat(r.status())
                .as("register missing email — expected 4xx, not 5xx, not 2xx")
                .isBetween(400, 499);
    }

    @Test
    @DisplayName("TC15 — Register with role=ADMIN in body must NOT result in an ADMIN account (privilege-escalation)")
    void tc15_roleAdminInBody_notHonored() {
        String email = Nonce.email("tc15");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "TC15 User");
        body.put("email", email);
        body.put("password", "TestPwd!2026");
        body.put("phone", Nonce.phone());
        body.put("role", "ADMIN");

        Http.Response r = Http.request(USER_BASE, "/api/auth/register").json(body).post();

        assertThat(r.status()).as("register with role=ADMIN — must not 5xx").isLessThan(500);

        if (r.status() >= 200 && r.status() < 300) {
            String token = r.json().path("token").asText(null);
            assertThat(token).as("token returned on 2xx").isNotBlank();
            assertThat(JwtClaims.roleOf(token))
                    .as("role in JWT must not be ADMIN for a self-registered user")
                    .isNotEqualToIgnoringCase("ADMIN");
        }
    }

    @Test
    @DisplayName("TC16 — Login with empty password returns 4xx (negative path)")
    void tc16_emptyPassword_returns4xx() {
        // setup: register a real user first so the login flow reaches the password check.
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc16");

        Http.Response r = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", me.email(), "password", ""))
                .post();

        assertThat(r.status())
                .as("login empty password — must be 4xx, never 2xx or 5xx")
                .isBetween(400, 499);
    }
}
