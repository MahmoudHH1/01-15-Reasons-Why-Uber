package com.team01.uber.tests.designpatterns;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Nonce;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-6 Factory — TC412..TC419 (8 TCs).
 *
 * <p>Per docs/m3/design-patterns.md (DP-6):
 * <ul>
 *   <li>{@code MongoEvent} interface with 4 methods (TC412).</li>
 *   <li>5 concrete event classes implement {@code MongoEvent} (TC413).</li>
 *   <li>{@code EventFactory.createEvent(EventType, Map) → MongoEvent} (TC414).</li>
 *   <li>AUTH dispatch → AuthEvent with fields populated (TC415).</li>
 *   <li>All 5 EventTypes dispatch correctly (TC416).</li>
 *   <li>PAYMENT_AUDIT exposes method + amount (TC417).</li>
 *   <li>Register integration → MongoDB doc shape matches factory output (TC418).</li>
 *   <li>No {@code new XEvent(...)} in service classes (TC419).</li>
 * </ul>
 *
 * <p>{@code MongoEvent}, {@code EventFactory}, and the 5 event classes live in
 * individual service modules (not in the shared {@code contracts} module on the
 * test classpath), so reflection / unit-level instantiation isn't reachable from
 * this black-box test module. Behavioral TC418 IS reachable — it inspects the
 * Mongo {@code auth_events} document the factory should produce on register.
 */
@DisplayName("DP-6 Factory — Mongo event creation")
class DpFactoryTest extends BaseHttpTest {

    @Test
    @Disabled("DEFERRED: structural reflection on per-service classpath (MongoEvent interface lives in user-service / driver-service / etc., not contracts) — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC412 — DP-6 Factory: MongoEvent interface")
    void tc412_mongoEventInterface() {
        // Structural: reflection on MongoEvent — 4 methods with correct return types.
    }

    @Test
    @Disabled("DEFERRED: structural reflection across all 5 service classpaths for AuthEvent, DriverEvent, RideEvent, LocationEvent, PaymentAuditEvent — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC413 — DP-6 Factory: 5 event classes implement MongoEvent")
    void tc413_fiveEventClassesImplementMongoEvent() {
        // Structural: reflection assert each event class implements MongoEvent.
    }

    @Test
    @Disabled("DEFERRED: structural reflection on per-service classpath (EventFactory) — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC414 — DP-6 Factory: createEvent(EventType, Map) signature")
    void tc414_createEventSignature() {
        // Structural: reflection assert createEvent(EventType, Map) → MongoEvent.
    }

    @Test
    @Disabled("DEFERRED: unit-level instantiation of EventFactory on per-service classpath — covered by service-side unit tests + bash test layer")
    @DisplayName("TC415 — DP-6 Factory: createEvent(AUTH, ...) returns AuthEvent")
    void tc415_createEventAuthReturnsAuthEvent() {
        // Unit test: EventFactory.createEvent(EventType.AUTH, params) instanceof AuthEvent.
    }

    @Test
    @Disabled("DEFERRED: unit-level instantiation of EventFactory on per-service classpath — covered by service-side unit tests + bash test layer")
    @DisplayName("TC416 — DP-6 Factory: all 5 EventTypes dispatch correctly")
    void tc416_allFiveEventTypesDispatch() {
        // Unit test: createEvent dispatch for AUTH/DRIVER/RIDE/LOCATION/PAYMENT_AUDIT.
    }

    @Test
    @Disabled("DEFERRED: unit-level instantiation of EventFactory on payment-service classpath — covered by service-side unit tests + bash test layer")
    @DisplayName("TC417 — DP-6 Factory: PAYMENT_AUDIT exposes method+amount")
    void tc417_paymentAuditExposesMethodAndAmount() {
        // Unit test: PaymentAuditEvent has method + amount populated.
    }

    @Test
    @DisplayName("TC418 — DP-6 Factory: register integration matches factory output")
    void tc418_registerIntegrationMatchesFactoryShape() {
        // Behavioral: register → assert auth_events doc has factory-produced shape
        // (action=REGISTERED, userId, timestamp).
        String email = Nonce.email("tc418");
        Http.Response r = Http.request(USER_BASE, "/api/auth/register")
                .json(Map.of(
                        "name", "TC418 User",
                        "email", email,
                        "password", "TestPwd!2026",
                        "phone", Nonce.phone()))
                .post();
        assertThat(r.status()).as("seed register").isBetween(200, 299);
        long uid = JwtClaims.uidOf(r.json().path("token").asText());

        // Wait for the async Observer→Factory→Mongo write to land.
        long observed = Mongo.countAtLeast(
                "auth_events",
                Map.of("userId", uid, "action", "REGISTERED"),
                1,
                Duration.ofSeconds(10));
        assertThat(observed)
                .as("at least one REGISTERED doc landed for userId=" + uid)
                .isGreaterThanOrEqualTo(1);

        // Inspect the doc shape — factory output guarantees action/userId fields.
        List<Document> docs = Mongo.findRecent(
                "auth_events",
                Map.of("userId", uid, "action", "REGISTERED"),
                1);
        assertThat(docs).as("at least one REGISTERED doc fetched").hasSizeGreaterThanOrEqualTo(1);

        Document doc = docs.get(0);
        assertThat(doc.getString("action"))
                .as("auth_events doc.action — factory writes the action string")
                .isEqualTo("REGISTERED");
        assertThat(doc.get("userId"))
                .as("auth_events doc.userId — factory writes the userId field")
                .isNotNull();
        // Timestamp field — factory MongoEvent contract per design-patterns.md
        // declares getTimestamp() → LocalDateTime. Doc must carry a non-null
        // timestamp (named 'timestamp' or 'createdAt'). We tolerate either.
        boolean hasTimestamp = doc.containsKey("timestamp") || doc.containsKey("createdAt");
        assertThat(hasTimestamp)
                .as("auth_events doc must carry a timestamp (factory MongoEvent contract)")
                .isTrue();
    }

    @Test
    @Disabled("DEFERRED: source-scan across all 5 service classpaths for `new XEvent(` outside EventFactory.java — covered by bash test layer (pattern-verifier)")
    @DisplayName("TC419 — DP-6 Factory: no `new XEvent(...)` in services")
    void tc419_noDirectEventConstructorsInServices() {
        // Source-scan: grep for `new AuthEvent(`, `new DriverEvent(`, etc. across services.
    }
}
