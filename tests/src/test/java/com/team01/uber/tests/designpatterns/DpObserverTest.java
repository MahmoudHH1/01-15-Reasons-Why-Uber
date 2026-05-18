package com.team01.uber.tests.designpatterns;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-2 Observer — TC386..TC392 (7 TCs).
 *
 * <p>Per docs/m3/design-patterns.md (DP-2):
 * <ul>
 *   <li>{@code EntityObserver} interface with {@code onEvent(String, Object)}.</li>
 *   <li>{@code MongoEventLogger} concrete observer bound to a fixed {@code EventType}.</li>
 *   <li>Subject mixin/base with register / unregister / notifyObservers.</li>
 *   <li>No {@code @EventListener} writes to MongoDB.</li>
 *   <li>M1 retrofits (e.g. S1-F2 PUT preferences) fire observers.</li>
 * </ul>
 *
 * <p>Behavioral TCs (TC389/TC390/TC391) hit the actual auth_events collection
 * through {@link Mongo}.countAtLeast. Structural / source-scan / unit-test TCs
 * (TC386/TC387/TC388/TC392) defer to the bash test layer.
 */
@DisplayName("DP-2 Observer — EntityObserver + MongoEventLogger")
class DpObserverTest extends BaseHttpTest {

    private record Authed(long uid, String token) {}

    private Authed registerRider(String tag) {
        String email = Nonce.email(tag);
        Http.Response r = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", tag + " User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(r.status()).as("seed register " + tag).isBetween(200, 299);
        String token = r.json().path("token").asText();
        return new Authed(JwtClaims.uidOf(token), token);
    }

    @Test
    @Disabled("DEFERRED: structural reflection on user-service classpath — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC386 — DP-2 Observer: EntityObserver interface")
    void tc386_entityObserverInterface() {
        // Structural: reflection load EntityObserver, assert interface + onEvent(String, Object) declared.
    }

    @Test
    @Disabled("DEFERRED: structural reflection on user-service classpath — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC387 — DP-2 Observer: MongoEventLogger implements EntityObserver")
    void tc387_mongoEventLoggerImplementsEntityObserver() {
        // Structural: reflection assert MongoEventLogger implements EntityObserver.
    }

    @Test
    @Disabled("DEFERRED: static class-file scan across all 5 services for @EventListener + Mongo writes — covered by bash test layer")
    @DisplayName("TC388 — DP-2 Observer: no @EventListener writes to MongoDB (Spring vs GoF)")
    void tc388_noEventListenerWritesToMongo() {
        // Source/class-file scan all 5 services. No @EventListener method may write to Mongo.
    }

    @Test
    @DisplayName("TC389 — DP-2 Observer: register triggers REGISTERED in auth_events")
    void tc389_registerEmitsRegisteredAuthEvent() {
        String email = Nonce.email("tc389");
        Http.Response r = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "TC389 User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(r.status()).as("register").isBetween(200, 299);
        long uid = JwtClaims.uidOf(r.json().path("token").asText());

        long observed = Mongo.countAtLeast(
                "auth_events",
                Map.of("userId", uid, "action", "REGISTERED"),
                1,
                Duration.ofSeconds(10));

        assertThat(observed)
                .as("REGISTERED document for userId=" + uid + " in auth_events")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC390 — DP-2 Observer: login triggers LOGGED_IN")
    void tc390_loginEmitsLoggedInAuthEvent() {
        String email = Nonce.email("tc390");
        String password = "TestPwd!2026";
        Http.Response register = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "TC390 User",
                        "email", email,
                        "password", password,
                        "phone", Nonce.phone()))
                .post();
        assertThat(register.status()).as("seed register").isBetween(200, 299);
        long uid = JwtClaims.uidOf(register.json().path("token").asText());

        // Snapshot then login → assert delta on LOGGED_IN count.
        long before = Mongo.count("auth_events", Map.of("userId", uid, "action", "LOGGED_IN"));

        Http.Response login = Http.request(USER_BASE, "/api/auth/login")
                .json(Map.of("email", email, "password", password))
                .post();
        assertThat(login.status()).as("login").isBetween(200, 299);

        long after = Mongo.countAtLeast(
                "auth_events",
                Map.of("userId", uid, "action", "LOGGED_IN"),
                before + 1,
                Duration.ofSeconds(10));

        assertThat(after)
                .as("auth_events LOGGED_IN count grows on login")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("TC391 — DP-2 Observer: M1 retrofit (S1-F2) emits event")
    void tc391_m1PreferencesRetrofitEmitsAuthEvent() {
        Authed me = registerRider("tc391");
        long before = Mongo.count("auth_events", Map.of("userId", me.uid()));

        Http.Response pref = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(Map.of("language", "ar", "notifications", false))
                .put();

        // Endpoint may return 200 or 204 per the bash test.
        assertThat(pref.status())
                .as("PUT preferences M1 retrofit")
                .isBetween(200, 299);

        long after = Mongo.countAtLeast(
                "auth_events",
                Map.of("userId", me.uid()),
                before + 1,
                Duration.ofSeconds(10));

        assertThat(after)
                .as("auth_events count grows on M1 PUT preferences (S1-F2 retrofit)")
                .isGreaterThan(before);
    }

    @Test
    @Disabled("DEFERRED: requires unit-level subject/observer instantiation — not reachable via HTTP black box (covered by bash test layer + service-side unit tests)")
    @DisplayName("TC392 — DP-2 Observer: unregister stops events (proves chain path)")
    void tc392_unregisterStopsEvents() {
        // Unit test: construct subject, register/unregister MongoEventLogger, trigger write, assert no doc.
    }
}
