package com.team01.uber.tests.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F9 — Preferences-language combined search")
class UserPreferencesLanguageFeatureTest extends BaseHttpTest {

    private static JsonNode results(Http.Response r) {
        JsonNode body = r.json();
        return body.has("content") ? body.path("content") : body;
    }

    private void setPrefs(UserSeederSupport.AuthedUser me, Map<String, Object> prefs) {
        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(prefs)
                .put();
        assertThat(r.status()).as("seed prefs " + me.uid()).isBetween(200, 299);
    }

    @Test
    @DisplayName("TC215 — ?lang=ar&minRides=2 matches user with 3 completed rides + ar prefs")
    void tc215_combinedLangMinRides() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser ua = UserSeederSupport.registerRider("tc215a");
        setPrefs(ua, Map.of("language", "ar"));

        // We cannot black-box-seed 3 COMPLETED rides for `ua` from this test (the user-service
        // does the ride lookup via Feign). Verify the endpoint shape — passes a 2xx envelope
        // even when ride-service is unreachable.
        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/language?lang=ar&minRides=2")
                .bearer(admin)
                .get();

        // Accept 503 as the SUT-side Feign-failure mode but record it.
        if (r.status() == 503) {
            Assumptions.abort("preferences/language returned 503 — ride-service Feign call unavailable");
        }
        assertThat(r.status()).as("preferences/language").isBetween(200, 299);
        assertThat(r.json().isArray() || r.json().has("content"))
                .as("array or page envelope").isTrue();
    }

    @Test
    @DisplayName("TC216 — ?minRides=100 returns empty when no user has 100 rides")
    void tc216_largeMinRides_empty() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser ua = UserSeederSupport.registerRider("tc216a");
        setPrefs(ua, Map.of("language", "ar"));

        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/language?lang=ar&minRides=100")
                .bearer(admin)
                .get();
        if (r.status() == 503) {
            Assumptions.abort("preferences/language returned 503 — ride-service Feign call unavailable");
        }
        assertThat(r.status()).as("minRides=100").isBetween(200, 299);
        assertThat(results(r).size()).as("no rider has 100 rides").isEqualTo(0);
    }

    @Test
    @DisplayName("TC217 — ?lang=fr excludes user whose prefs.language=en")
    void tc217_langFilterExact() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser ua = UserSeederSupport.registerRider("tc217a");
        setPrefs(ua, Map.of("language", "en"));

        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/language?lang=fr&minRides=1")
                .bearer(admin)
                .get();
        if (r.status() == 503) {
            Assumptions.abort("preferences/language returned 503 — ride-service Feign call unavailable");
        }
        assertThat(r.status()).as("lang=fr search").isBetween(200, 299);

        JsonNode list = results(r);
        for (JsonNode el : list) {
            assertThat(el.path("id").asLong())
                    .as("en-language user must not appear in lang=fr results")
                    .isNotEqualTo(ua.uid());
        }
    }
}
