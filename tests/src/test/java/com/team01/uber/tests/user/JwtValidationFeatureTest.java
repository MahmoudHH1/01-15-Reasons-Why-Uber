package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.JwtTestHelper;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cross-cutting — JWT validation on protected routes")
class JwtValidationFeatureTest extends BaseHttpTest {

    private static final String PROTECTED_GET = "/api/users";

    private String seedAndGetToken() {
        String email = Nonce.email("jwt");
        String password = "TestPwd!2026";
        Http.Response r = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "JWT Test",
                        "email", email,
                        "password", password,
                        "phone", Nonce.phone()))
                .post();
        assertThat(r.status()).as("seed register").isBetween(200, 299);
        return r.json().path("token").asText();
    }

    @Test
    @DisplayName("TC08 — Tampered JWT signature is rejected with 401")
    void tc08_tamperedSignature_returns401() {
        String validToken = seedAndGetToken();
        String[] parts = validToken.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "XXXX";

        Http.Response response = Http.request(USER_BASE, PROTECTED_GET)
                .bearer(tampered)
                .get();

        assertThat(response.status()).as("tampered signature").isEqualTo(401);
    }

    @Test
    @DisplayName("TC10 — Empty Bearer token returns 401")
    void tc10_emptyBearer_returns401() {
        Http.Response response = Http.request(USER_BASE, PROTECTED_GET)
                .header("Authorization", "Bearer ")
                .get();

        assertThat(response.status()).as("empty bearer").isEqualTo(401);
    }

    @Test
    @DisplayName("TC11 — Non-Bearer scheme (Basic) returns 401")
    void tc11_basicAuthScheme_returns401() {
        Http.Response response = Http.request(USER_BASE, PROTECTED_GET)
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .get();

        assertThat(response.status()).as("basic scheme").isEqualTo(401);
    }

    @Test
    @DisplayName("Expired JWT is rejected with 401 (defense-in-depth)")
    void expiredToken_returns401() {
        long uid = JwtClaims.uidOf(seedAndGetToken());
        String expired = JwtTestHelper.expiredToken(uid, "expired@grader.testgen.io", "RIDER");

        Http.Response response = Http.request(USER_BASE, PROTECTED_GET)
                .bearer(expired)
                .get();

        assertThat(response.status()).as("expired token").isEqualTo(401);
    }
}
