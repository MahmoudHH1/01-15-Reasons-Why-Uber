package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F12 — User CRUD with auth + IDOR guards")
class UserCrudFeatureTest extends BaseHttpTest {

    private record AuthedUser(long uid, String email, String token) {}

    private AuthedUser register(String tag) {
        String email = Nonce.email(tag);
        Http.Response r = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", tag + " User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(r.status()).as("seed register for " + tag).isBetween(200, 299);
        String token = r.json().path("token").asText();
        return new AuthedUser(JwtClaims.uidOf(token), email, token);
    }

    @Test
    @DisplayName("TC03 — GET /api/users/{ownId} with valid JWT returns 2xx + user body")
    void tc03_readOwnProfile_returns2xxWithBody() {
        AuthedUser me = register("tc03");

        Http.Response response = Http.request(USER_BASE, "/api/users/" + me.uid())
                .bearer(me.token())
                .get();

        assertThat(response.status()).as("read own profile").isBetween(200, 299);
        assertThat(response.json().path("email").asText()).isEqualTo(me.email());
        assertThat(response.json().path("id").asLong()).isEqualTo(me.uid());
    }

    @Test
    @DisplayName("TC17 — User A cannot READ User B's profile (IDOR blocked)")
    void tc17_idorRead_blockedFor4xx() {
        AuthedUser alice = register("tc17a");
        AuthedUser bob = register("tc17b");

        Http.Response response = Http.request(USER_BASE, "/api/users/" + bob.uid())
                .bearer(alice.token())
                .get();

        assertThat(response.status())
                .as("Alice reading Bob — must be 403 (or 404 if obscured)")
                .isBetween(400, 499);
    }

    @Test
    @DisplayName("TC20 — User A can UPDATE their own profile (happy path)")
    void tc20_updateOwnProfile_returns2xx() {
        AuthedUser me = register("tc20");

        Map<String, Object> update = Map.of(
                "name", "Updated TC20 Name",
                "email", me.email(),
                "password", "TestPwd!2026",
                "phone", Nonce.phone(),
                "role", "RIDER"
        );

        Http.Response response = Http.request(USER_BASE, "/api/users/" + me.uid())
                .bearer(me.token())
                .json(update)
                .put();

        assertThat(response.status()).as("update own profile").isBetween(200, 299);
        assertThat(response.json().path("name").asText()).isEqualTo("Updated TC20 Name");
    }
}
