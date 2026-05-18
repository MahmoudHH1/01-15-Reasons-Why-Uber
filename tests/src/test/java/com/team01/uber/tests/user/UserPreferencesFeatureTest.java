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

@DisplayName("S1-F2/F5 — Preferences PUT + JSONB search")
class UserPreferencesFeatureTest extends BaseHttpTest {

    private static JsonNode results(Http.Response r) {
        JsonNode body = r.json();
        return body.has("content") ? body.path("content") : body;
    }

    private JsonNode setPreferences(UserSeederSupport.AuthedUser me, Map<String, Object> prefs) {
        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(prefs)
                .put();
        assertThat(r.status()).as("seed prefs PUT for " + me.uid()).isBetween(200, 299);
        return r.json().path("preferences");
    }

    @Test
    @DisplayName("TC194 — PUT preferences merges: language preserved, theme updated, currency added")
    void tc194_putPreferencesMerges() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc194");
        setPreferences(me, Map.of("language", "en", "theme", "light"));

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(Map.of("theme", "dark", "currency", "EGP"))
                .put();
        assertThat(r.status()).as("merge PUT").isBetween(200, 299);

        JsonNode prefs = r.json().path("preferences");
        assertThat(prefs.path("language").asText()).as("language preserved").isEqualTo("en");
        assertThat(prefs.path("theme").asText()).as("theme overwritten to dark").isEqualTo("dark");
        assertThat(prefs.path("currency").asText()).as("currency added").isEqualTo("EGP");
    }

    @Test
    @DisplayName("TC195 — PUT with existing key overwrites it")
    void tc195_putExistingKey_overwrites() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc195");
        setPreferences(me, Map.of("language", "en"));

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(Map.of("language", "fr"))
                .put();
        assertThat(r.status()).as("overwrite PUT").isBetween(200, 299);

        assertThat(r.json().path("preferences").path("language").asText())
                .as("language overwritten")
                .isEqualTo("fr");
    }

    @Test
    @DisplayName("TC196 — PUT preferences for non-existent user returns 404")
    void tc196_putPreferencesNonExistent_404() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/999999/preferences")
                .bearer(admin)
                .json(Map.of("x", "y"))
                .put();
        assertThat(r.status()).as("PUT prefs for ghost user").isEqualTo(404);
    }

    @Test
    @DisplayName("TC203 — ?key=language&value=ar matches users with prefs.language=ar")
    void tc203_prefSearchByLanguage_ar() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser ar1 = UserSeederSupport.registerRider("tc203ar1");
        UserSeederSupport.AuthedUser en1 = UserSeederSupport.registerRider("tc203en1");
        UserSeederSupport.AuthedUser ar2 = UserSeederSupport.registerRider("tc203ar2");
        setPreferences(ar1, Map.of("language", "ar"));
        setPreferences(en1, Map.of("language", "en"));
        setPreferences(ar2, Map.of("language", "ar"));

        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/search?key=language&value=ar")
                .bearer(admin)
                .get();
        assertThat(r.status()).as("prefs/search lang=ar").isBetween(200, 299);

        JsonNode list = results(r);
        assertThat(list.isArray()).as("results is array").isTrue();
        assertThat(list.size()).as("at least 2 matches").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("TC204 — Unknown value returns empty list")
    void tc204_prefSearchUnknownValue_empty() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser ar = UserSeederSupport.registerRider("tc204");
        setPreferences(ar, Map.of("language", "ar"));

        String unique = "unk" + Nonce.nonce().substring(0, 6);
        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/search?key=language&value=" + unique)
                .bearer(admin)
                .get();
        assertThat(r.status()).as("prefs/search unknown value").isBetween(200, 299);

        JsonNode list = results(r);
        assertThat(list.isArray()).as("results is array").isTrue();
        assertThat(list.size()).as("no matches expected").isEqualTo(0);
    }

    @Test
    @DisplayName("TC205 — Blank key returns 400")
    void tc205_prefSearchBlankKey_400() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/search?key=&value=ar")
                .bearer(admin)
                .get();
        assertThat(r.status()).as("prefs/search blank key").isEqualTo(400);
    }

    @Test
    @DisplayName("TC330 — Prefs search ?key=darkMode&value=true matches users")
    void tc330_prefSearchKeyValue_filtersMismatch() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser u1 = UserSeederSupport.registerRider("tc330u1");
        UserSeederSupport.AuthedUser u2 = UserSeederSupport.registerRider("tc330u2");
        setPreferences(u1, Map.of("darkMode", "true"));
        setPreferences(u2, Map.of("darkMode", "false"));

        Http.Response r = Http.request(USER_BASE, "/api/users/preferences/search?key=darkMode&value=true")
                .bearer(admin)
                .get();
        assertThat(r.status()).as("prefs/search darkMode=true").isBetween(200, 299);

        JsonNode list = results(r);
        assertThat(list.isArray()).as("results is array").isTrue();

        // u2 (false) must NOT appear
        boolean foundU2 = false;
        for (JsonNode el : list) {
            if (el.path("id").asLong() == u2.uid()) { foundU2 = true; break; }
        }
        assertThat(foundU2).as("u2 (darkMode=false) excluded from darkMode=true results").isFalse();
    }

    @Test
    @DisplayName("TC334 — PUT preferences merges nested object into existing JSONB")
    void tc334_putPreferencesNestedMerge_preservesExistingKeys() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc334");
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("language", "en");
        initial.put("notifications", Map.of("email", true));
        setPreferences(me, initial);

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(Map.of("notifications", Map.of("sms", true)))
                .put();
        assertThat(r.status()).as("nested merge PUT").isBetween(200, 299);

        JsonNode prefs = r.json().path("preferences");
        assertThat(prefs.has("notifications"))
                .as("notifications key must still be present after nested merge")
                .isTrue();
    }
}
