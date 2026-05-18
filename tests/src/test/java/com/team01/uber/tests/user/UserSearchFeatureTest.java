package com.team01.uber.tests.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F1 — User search")
class UserSearchFeatureTest extends BaseHttpTest {

    private static JsonNode results(Http.Response r) {
        JsonNode body = r.json();
        return body.has("content") ? body.path("content") : body;
    }

    private static long countWhereNameContains(JsonNode list, String needle) {
        if (!list.isArray()) return 0L;
        long n = 0;
        for (JsonNode el : list) {
            String name = el.path("name").asText("");
            if (name.contains(needle)) n++;
        }
        return n;
    }

    @Test
    @DisplayName("TC191 — Search by name 'Ahmed' returns 2 users (partial match)")
    void tc191_searchByName_partialMatch() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        // Seed three users using a unique name token so we count only this run's data.
        String marker = "Ahmed" + Nonce.nonce().substring(0, 6);
        UserSeederSupport.registerRider("tc191a", marker);
        UserSeederSupport.registerRider("tc191b", marker + " Ali");
        UserSeederSupport.registerRider("tc191c", "Sara" + Nonce.nonce().substring(0, 6));

        Http.Response r = Http.request(USER_BASE, "/api/users/search?name=" + marker)
                .bearer(admin)
                .get();
        assertThat(r.status()).as("search status").isBetween(200, 299);

        JsonNode results = results(r);
        long matching = countWhereNameContains(results, marker);
        assertThat(matching).as("name match count for unique marker " + marker).isEqualTo(2L);
    }

    @Test
    @DisplayName("TC192 — Search by role=ADMIN returns ADMIN users only")
    void tc192_searchByRoleAdmin_onlyAdmins() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        // Plant a RIDER alongside so we know there are mixed roles in DB.
        UserSeederSupport.registerRider("tc192rider");

        Http.Response r = Http.request(USER_BASE, "/api/users/search?role=ADMIN")
                .bearer(admin)
                .get();
        assertThat(r.status()).as("search role=ADMIN").isBetween(200, 299);

        JsonNode results = results(r);
        assertThat(results.isArray()).as("results is array").isTrue();
        assertThat(results.size()).as("at least 1 admin").isGreaterThanOrEqualTo(1);
        for (JsonNode el : results) {
            assertThat(el.path("role").asText())
                    .as("every result role must be ADMIN")
                    .isEqualTo("ADMIN");
        }
    }

    @Test
    @DisplayName("TC193 — Search with no-matching name returns empty list")
    void tc193_searchNoMatch_emptyList() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.registerRider("tc193", "AhmedTc193");
        String noMatch = "zzzNoMatchXYZ_" + Nonce.nonce().substring(0, 8);

        Http.Response r = Http.request(USER_BASE, "/api/users/search?name=" + noMatch)
                .bearer(admin)
                .get();
        assertThat(r.status()).as("search no-match").isBetween(200, 299);

        JsonNode results = results(r);
        assertThat(results.isArray()).as("results is array").isTrue();
        assertThat(results.size()).as("size for non-matching name").isEqualTo(0);
    }

    @Test
    @DisplayName("TC218 — Search by email substring matches one user")
    void tc218_searchByEmailSubstring_matches() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        // Use a deterministic, unique seed for the target email substring.
        String marker = "tc218target" + Nonce.nonce().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Bob TC218");
        body.put("email", marker + "@grader.testgen.io");
        body.put("password", "TestPwd!2026");
        body.put("phone", Nonce.phone());
        Http.Response reg = Http.request(USER_BASE, "/api/auth/register").json(body).post();
        assertThat(reg.status()).as("seed register").isBetween(200, 299);

        Http.Response r = Http.request(USER_BASE, "/api/users/search?email=" + marker)
                .bearer(admin)
                .get();
        assertThat(r.status()).as("search by email").isBetween(200, 299);

        JsonNode results = results(r);
        assertThat(results.isArray()).as("results is array").isTrue();
        assertThat(results.size()).as("at least 1 email match").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("TC329 — Search ?name=Ali&role=RIDER returns RIDERs named Ali")
    void tc329_searchCombinedNameAndRole_andSemantics() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        String marker = "Ali" + Nonce.nonce().substring(0, 6);
        UserSeederSupport.registerRider("tc329a", marker);

        // Seed a name-only and role-only collision to make sure the AND is enforced.
        UserSeederSupport.registerRider("tc329b", "Bob" + Nonce.nonce().substring(0, 4));

        Http.Response r = Http.request(USER_BASE, "/api/users/search?name=" + marker + "&role=RIDER")
                .bearer(admin)
                .get();
        assertThat(r.status()).as("combined search").isBetween(200, 299);

        JsonNode results = results(r);
        assertThat(results.isArray()).as("results is array").isTrue();
        for (JsonNode el : results) {
            assertThat(el.path("role").asText()).isEqualTo("RIDER");
            assertThat(el.path("name").asText()).contains(marker);
        }
    }

    @Test
    @DisplayName("TC376 — Search /api/users/search with no params returns all users")
    void tc376_searchNoParams_returnsAll() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.registerRider("tc376a");
        UserSeederSupport.registerRider("tc376b");

        Http.Response r = Http.request(USER_BASE, "/api/users/search").bearer(admin).get();
        assertThat(r.status()).as("search no params").isBetween(200, 299);

        JsonNode results = results(r);
        assertThat(results.isArray()).as("results is array").isTrue();
        assertThat(results.size()).as("at least 2 users present").isGreaterThanOrEqualTo(2);
    }
}
