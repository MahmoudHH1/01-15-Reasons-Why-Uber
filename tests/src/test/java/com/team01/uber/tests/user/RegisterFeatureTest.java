package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F1 — Register")
class RegisterFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC01 — POST /api/auth/register with fresh email returns 2xx + JWT token")
    void tc01_freshEmail_returns2xxAndJwt() {
        String email = Nonce.email("tc01");
        Map<String, Object> body = Map.of(
                "name", "TC01 User",
                "email", email,
                "password", "TestPwd!2026",
                "phone", Nonce.phone()
        );

        Http.Response response = Http.request(USER_BASE, "/api/auth/register")
                .json(body)
                .post();

        assertThat(response.status())
                .as("Register fresh email — expected 2xx")
                .isBetween(200, 299);

        String token = response.json().path("token").asText(null);
        long expiresIn = response.json().path("expiresIn").asLong(-1);

        assertThat(token).as("token in body").isNotBlank();
        assertThat(expiresIn).as("expiresIn > 0").isPositive();
        assertThat(JwtClaims.emailOf(token)).isEqualTo(email);
        assertThat(JwtClaims.roleOf(token)).isEqualTo("RIDER");
        assertThat(JwtClaims.uidOf(token)).isPositive();
    }

    @Test
    @DisplayName("TC04 — POST /api/auth/register with duplicate email returns 4xx")
    void tc04_duplicateEmail_returns4xx() {
        String email = Nonce.email("tc04");
        Map<String, Object> firstBody = Map.of(
                "name", "TC04 User",
                "email", email,
                "password", "TestPwd!2026",
                "phone", Nonce.phone()
        );

        Http.Response first = Http.request(USER_BASE, "/api/auth/register")
                .json(firstBody)
                .post();
        assertThat(first.status()).as("first register").isBetween(200, 299);

        Map<String, Object> duplicate = Map.of(
                "name", "TC04 Dup",
                "email", email,
                "password", "TestPwd!2026",
                "phone", Nonce.phone()
        );

        Http.Response second = Http.request(USER_BASE, "/api/auth/register")
                .json(duplicate)
                .post();

        assertThat(second.status())
                .as("duplicate register — expected 4xx")
                .isBetween(400, 499);
    }
}
