package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F10/F11 — Login")
class LoginFeatureTest extends BaseHttpTest {

    private record SeededUser(String email, String password) {}

    private SeededUser seedUser(String tag) {
        String email = Nonce.email(tag);
        String password = "TestPwd!2026";
        Map<String, Object> body = Map.of(
                "name", tag + " User",
                "email", email,
                "password", password,
                "phone", Nonce.phone()
        );
        Http.Response r = Http.request(USER_BASE, "/api/auth/register").json(body).post();
        assertThat(r.status()).as("seed register for " + tag).isBetween(200, 299);
        return new SeededUser(email, password);
    }

    @Test
    @DisplayName("TC02 — POST /api/auth/login with valid credentials returns 2xx + JWT")
    void tc02_validCredentials_returns2xxAndJwt() {
        SeededUser user = seedUser("tc02login");

        Http.Response response = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", user.email(), "password", user.password()))
                .post();

        assertThat(response.status()).as("login happy path").isBetween(200, 299);

        String token = response.json().path("token").asText(null);
        assertThat(token).isNotBlank();
        assertThat(JwtClaims.emailOf(token)).isEqualTo(user.email());
        assertThat(response.json().path("expiresIn").asLong(-1)).isPositive();
    }

    @Test
    @DisplayName("TC05 — POST /api/auth/login with wrong password returns 401")
    void tc05_wrongPassword_returns401() {
        SeededUser user = seedUser("tc05");

        Http.Response response = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", user.email(), "password", "WrongPwd!2026"))
                .post();

        assertThat(response.status()).as("wrong password").isEqualTo(401);
    }

    @Test
    @DisplayName("TC09 — POST /api/auth/login with non-existent email returns 401")
    void tc09_nonExistentEmail_returns401() {
        Http.Response response = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", Nonce.email("tc09_missing"), "password", "TestPwd!2026"))
                .post();

        assertThat(response.status()).as("non-existent email").isEqualTo(401);
    }
}
