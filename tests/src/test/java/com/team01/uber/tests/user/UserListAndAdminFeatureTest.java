package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDOR write/delete blockers (TC18, TC19) and ADMIN override on the CRUD endpoints
 * (TC21, TC22, TC23). TC17 + TC20 are already in {@code UserCrudFeatureTest}.
 */
@DisplayName("S1-F8/F9 — User IDOR + admin override")
class UserListAndAdminFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC18 — Cross-user IDOR: User A cannot UPDATE User B's profile (negative path)")
    void tc18_idorUpdate_blocked() {
        UserSeederSupport.AuthedUser alice = UserSeederSupport.registerRider("tc18a");
        UserSeederSupport.AuthedUser bob   = UserSeederSupport.registerRider("tc18b", "TC18 B Original");

        Map<String, Object> hijack = new LinkedHashMap<>();
        hijack.put("name", "TC18 HIJACK");
        hijack.put("email", bob.email());
        hijack.put("password", "TestPwd!2026");
        hijack.put("phone", Nonce.phone());

        Http.Response r = Http.request(USER_BASE, "/api/users/" + bob.uid())
                .bearer(alice.token())
                .json(hijack)
                .put();

        assertThat(r.status())
                .as("Alice updating Bob — must be 403 or 404 (never 2xx or 5xx)")
                .isBetween(400, 499);

        // best-effort black-box "DB unchanged" check: read Bob via Bob's own token.
        Http.Response check = Http.request(USER_BASE, "/api/users/" + bob.uid())
                .bearer(bob.token())
                .get();
        if (check.status() >= 200 && check.status() < 300) {
            assertThat(check.json().path("name").asText())
                    .as("Bob's name must not be hijacked by Alice's PUT")
                    .isEqualTo("TC18 B Original");
        }
    }

    @Test
    @DisplayName("TC19 — Cross-user IDOR: User A cannot DELETE User B (negative path)")
    void tc19_idorDelete_blocked() {
        UserSeederSupport.AuthedUser alice = UserSeederSupport.registerRider("tc19a");
        UserSeederSupport.AuthedUser bob   = UserSeederSupport.registerRider("tc19b");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + bob.uid())
                .bearer(alice.token())
                .delete();

        assertThat(r.status())
                .as("Alice deleting Bob — must be 403 or 404 (never 2xx or 5xx)")
                .isBetween(400, 499);

        // Black-box "row still exists" check: Bob can still read his own profile.
        Http.Response check = Http.request(USER_BASE, "/api/users/" + bob.uid())
                .bearer(bob.token())
                .get();
        assertThat(check.status()).as("Bob's row must still exist after Alice's failed DELETE").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC21 — Admin override: admin can READ any user (happy path)")
    void tc21_adminCanReadAnyUser() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser customer = UserSeederSupport.registerRider("tc21cust");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + customer.uid())
                .bearer(admin)
                .get();

        assertThat(r.status()).as("admin reading customer").isBetween(200, 299);
        assertThat(r.json().isObject()).as("body is a JSON object").isTrue();
        assertThat(r.json().path("id").asLong()).isEqualTo(customer.uid());
    }

    @Test
    @DisplayName("TC22 — Admin override: admin can UPDATE any user (happy path)")
    void tc22_adminCanUpdateAnyUser() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser customer = UserSeederSupport.registerRider("tc22cust");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "TC22 Admin-Updated");
        body.put("email", customer.email());
        body.put("password", "TestPwd!2026");
        body.put("phone", Nonce.phone());

        Http.Response put = Http.request(USER_BASE, "/api/users/" + customer.uid())
                .bearer(admin)
                .json(body)
                .put();
        assertThat(put.status()).as("admin updating customer").isBetween(200, 299);

        // Verify the name change persisted (admin GET — black-box equivalent of JDBC SELECT name).
        Http.Response check = Http.request(USER_BASE, "/api/users/" + customer.uid())
                .bearer(admin)
                .get();
        assertThat(check.status()).as("admin re-read customer").isBetween(200, 299);
        assertThat(check.json().path("name").asText())
                .as("name persisted after admin PUT")
                .isEqualTo("TC22 Admin-Updated");
    }

    @Test
    @DisplayName("TC23 — Admin override: admin can HARD-DELETE any user (happy path, strict)")
    void tc23_adminCanHardDeleteAnyUser() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser customer = UserSeederSupport.registerRider("tc23cust");

        Http.Response del = Http.request(USER_BASE, "/api/users/" + customer.uid())
                .bearer(admin)
                .delete();
        assertThat(del.status()).as("admin deleting customer").isBetween(200, 299);

        Http.Response get = Http.request(USER_BASE, "/api/users/" + customer.uid())
                .bearer(admin)
                .get();
        assertThat(get.status())
                .as("admin GET after hard-delete — strictly 404")
                .isEqualTo(404);
    }
}
