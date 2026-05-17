package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.JwtClaims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F8 / S2-F9 — Driver documents: verify, expired-docs report.
 *
 * <p>Covers TC238..TC242, TC247, TC341, TC375 (8 TCs).
 */
@DisplayName("S2-F8 / S2-F9 — Driver documents")
class DriverDocumentsFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC238 — Admin verifies document; PG verified=true")
    void tc238_admin_verifies_documentTrue() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc238");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC238 Driver"));
        long docId = DriverSeederSupport.seedDocument(token, driverId, "LICENSE", "2030-01-01");

        String adminToken = DriverSeederSupport.adminTokenOrNull();
        if (adminToken == null) {
            // The DataSeeder admin needs to exist for this TC; if not, fail to surface the env gap.
            throw new AssertionError("ADMIN seed user not reachable — required for TC238");
        }
        long adminUid = JwtClaims.uidOf(adminToken);

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/" + docId + "/verify")
                .bearer(adminToken)
                .json(Map.of("verifiedBy", adminUid))
                .put();

        assertThat(r.status()).as("admin verify document").isBetween(200, 299);

        // Re-read the document and assert verified=true.
        Http.Response read = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/" + docId)
                .bearer(token)
                .get();
        assertThat(read.status()).as("read verified doc").isBetween(200, 299);
        assertThat(read.json().path("verified").asBoolean())
                .as("PG verified == true")
                .isTrue();
    }

    @Test
    @DisplayName("TC239 — Verify unknown document returns 404")
    void tc239_verifyUnknownDocument_returns404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc239");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC239 Driver"));

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/999999/verify")
                .bearer(rider.token())
                .json(Map.of("verifiedBy", rider.uid()))
                .put();

        assertThat(r.status()).as("verify unknown doc").isEqualTo(404);
    }

    @Test
    @DisplayName("TC240 — Expired-docs report lists drivers with past-expiry docs")
    void tc240_expiredDocsReport_listsExpired() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc240");
        String token = rider.token();
        long d1 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC240 D1"));
        long d2 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC240 D2"));
        DriverSeederSupport.seedDocument(token, d1, "LICENSE", "2020-01-01"); // expired
        DriverSeederSupport.seedDocument(token, d2, "LICENSE", "2030-01-01"); // future

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/documents/expired")
                .bearer(token)
                .get();
        assertThat(r.status()).as("expired-docs status").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();

        boolean d1Present = false;
        boolean d2Present = false;
        for (var node : r.json()) {
            long id = node.path("driverId").asLong();
            if (id == d1) d1Present = true;
            if (id == d2) d2Present = true;
        }
        assertThat(d1Present).as("expired d1 in list").isTrue();
        assertThat(d2Present).as("non-expired d2 absent").isFalse();
    }

    @Test
    @DisplayName("TC241 — Driver with all docs in date is excluded from expired-docs report")
    void tc241_inDateDocs_excluded() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc241");
        String token = rider.token();
        long d1 = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC241 D1"));
        DriverSeederSupport.seedDocument(token, d1, "INSURANCE", "2030-06-01"); // future

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/documents/expired")
                .bearer(token)
                .get();
        assertThat(r.status()).as("expired-docs status").isBetween(200, 299);
        assertThat(r.json().isArray()).isTrue();
        boolean present = false;
        for (var node : r.json()) {
            if (node.path("driverId").asLong() == d1) {
                present = true;
                break;
            }
        }
        assertThat(present).as("in-date driver excluded from expired report").isFalse();
    }

    @Test
    @DisplayName("TC242 — DriverDocumentAlertDTO.expiredCount counts past-expiry docs")
    void tc242_expiredCount_correct() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc242");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC242 Driver"));
        DriverSeederSupport.seedDocument(token, driverId, "LICENSE", "2020-01-01");      // expired
        DriverSeederSupport.seedDocument(token, driverId, "INSURANCE", "2020-06-01");    // expired
        DriverSeederSupport.seedDocument(token, driverId, "REGISTRATION", "2030-01-01"); // future

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/documents/expired")
                .bearer(token)
                .get();
        assertThat(r.status()).as("expired-docs status").isBetween(200, 299);
        boolean found = false;
        int expiredCount = -1;
        for (var node : r.json()) {
            if (node.path("driverId").asLong() == driverId) {
                found = true;
                expiredCount = node.path("expiredCount").asInt(-1);
                break;
            }
        }
        assertThat(found).as("driver present in expired list").isTrue();
        assertThat(expiredCount).as("expiredCount == 2 (LICENSE + INSURANCE expired, REG future)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("TC247 — Verify an already-verified document remains true (no-op)")
    void tc247_idempotentVerify_remainsTrue() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc247");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC247 Driver"));
        long docId = DriverSeederSupport.seedDocument(token, driverId, "LICENSE", "2030-01-01");

        String adminToken = DriverSeederSupport.adminTokenOrNull();
        if (adminToken == null) {
            throw new AssertionError("ADMIN seed user not reachable — required for TC247");
        }
        long adminUid = JwtClaims.uidOf(adminToken);

        // First verify.
        Http.Response first = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/" + docId + "/verify")
                .bearer(adminToken).json(Map.of("verifiedBy", adminUid)).put();
        assertThat(first.status()).as("first verify").isBetween(200, 299);

        // Second verify — must remain true and not 5xx.
        Http.Response second = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/" + docId + "/verify")
                .bearer(adminToken).json(Map.of("verifiedBy", adminUid)).put();
        assertThat(second.status()).as("second verify status < 500").isLessThan(500);

        Http.Response read = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/" + docId)
                .bearer(token).get();
        assertThat(read.json().path("verified").asBoolean())
                .as("re-verify keeps verified=true").isTrue();
    }

    @Test
    @DisplayName("TC341 — Verify document with rider token returns 403")
    void tc341_riderVerifyDoc_returns403() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc341");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("TC341 Driver"));
        long docId = DriverSeederSupport.seedDocument(token, driverId, "LICENSE", "2030-01-01");

        // Rider tries to verify with their own uid as verifiedBy — they are not ADMIN.
        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + driverId + "/documents/" + docId + "/verify")
                .bearer(token)
                .json(Map.of("verifiedBy", rider.uid()))
                .put();

        assertThat(r.status()).as("rider verifying as non-admin").isEqualTo(403);
    }

    @Test
    @DisplayName("TC375 — Expired-docs DTO includes driverName")
    void tc375_expiredDocsDto_includesDriverName() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc375");
        String token = rider.token();
        long driverId = DriverSeederSupport.createDriver(token,
                DriverSeederSupport.driverBody("Expired Hassan TC375"));
        DriverSeederSupport.seedDocument(token, driverId, "LICENSE", "2020-01-01");

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/documents/expired")
                .bearer(token)
                .get();
        assertThat(r.status()).as("expired-docs status").isBetween(200, 299);

        boolean found = false;
        for (var node : r.json()) {
            if (node.path("driverId").asLong() == driverId) {
                String driverName = node.path("driverName").asText("");
                assertThat(driverName).as("driverName field non-empty")
                        .contains("Hassan");
                found = true;
                break;
            }
        }
        assertThat(found).as("seeded driver present in expired-docs report").isTrue();
    }
}
