package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Observer pattern wiring on the user-service: register + login should both
 * append a document to the {@code auth_events} MongoDB collection.
 *
 * <p>These TCs are not in the public TSV slice — they catch a regression in the Observer
 * carry-over from M2 (uber-m3.md:44) — so they are kept as deliberately scoped
 * "feature-events" tests that supplement the catalogue.
 */
@DisplayName("S1-F10/F11 — Observer auth_events on register / login")
class AuthEventsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("Register emits a REGISTERED auth_event for the new user")
    void register_emitsRegisteredEvent() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("authev_reg");

        long observed = Mongo.countAtLeast(
                "auth_events",
                Map.of("userId", me.uid(), "action", "REGISTERED"),
                1,
                Duration.ofSeconds(5));

        assertThat(observed).as("auth_events REGISTERED for userId " + me.uid()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Login emits a LOGGED_IN auth_event")
    void login_emitsLoggedInEvent() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("authev_login");

        Http.Response r = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", me.email(), "password", "TestPwd!2026"))
                .post();
        assertThat(r.status()).as("login").isBetween(200, 299);

        long observed = Mongo.countAtLeast(
                "auth_events",
                Map.of("userId", me.uid(), "action", "LOGGED_IN"),
                1,
                Duration.ofSeconds(5));

        assertThat(observed).as("auth_events LOGGED_IN for userId " + me.uid()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Wrong-password login does NOT emit a LOGGED_IN event")
    void wrongPasswordLogin_doesNotEmitEvent() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("authev_wrong");

        long before = Mongo.count("auth_events",
                Map.of("userId", me.uid(), "action", "LOGGED_IN"));

        Http.Response r = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", me.email(), "password", "WrongPwd!2026"))
                .post();
        assertThat(r.status()).as("wrong password login").isEqualTo(401);

        // Give time for an erroneous async append to land — there shouldn't be one.
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        long after = Mongo.count("auth_events",
                Map.of("userId", me.uid(), "action", "LOGGED_IN"));

        assertThat(after).as("LOGGED_IN events should not increment on auth failure").isEqualTo(before);
    }
}
