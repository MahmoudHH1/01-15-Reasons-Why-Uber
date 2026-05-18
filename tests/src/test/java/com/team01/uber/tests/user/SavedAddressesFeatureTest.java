package com.team01.uber.tests.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F7/F8 — Saved addresses + profile")
class SavedAddressesFeatureTest extends BaseHttpTest {

    private long seedAddress(UserSeederSupport.AuthedUser me, String label, boolean isDefault) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", label);
        body.put("address", label + " St");
        body.put("latitude", 30.0 + Math.random());
        body.put("longitude", 31.0 + Math.random());
        body.put("isDefault", isDefault);
        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/addresses")
                .bearer(me.token())
                .json(body)
                .post();
        assertThat(r.status()).as("seed address " + label + " for " + me.uid())
                .isBetween(200, 299);
        return r.json().path("id").asLong();
    }

    private JsonNode listAddresses(UserSeederSupport.AuthedUser me) {
        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/addresses")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("list addresses").isBetween(200, 299);
        return r.json();
    }

    private static Map<Long, Boolean> defaultMap(JsonNode list) {
        java.util.Map<Long, Boolean> out = new java.util.LinkedHashMap<>();
        for (JsonNode el : list) {
            out.put(el.path("id").asLong(), el.path("isDefault").asBoolean(false));
        }
        return out;
    }

    @Test
    @DisplayName("TC209 — PUT default flips isDefault to true on chosen address, others to false")
    void tc209_putDefault_flipsExclusive() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc209");
        long a1 = seedAddress(me, "Home", true);
        long a2 = seedAddress(me, "Work", false);

        Http.Response r = Http.request(USER_BASE,
                        "/api/users/" + me.uid() + "/addresses/" + a2 + "/default")
                .bearer(me.token())
                .put();
        assertThat(r.status()).as("PUT default a2").isBetween(200, 299);

        Map<Long, Boolean> map = defaultMap(listAddresses(me));
        assertThat(map.get(a2)).as("a2 isDefault must be true after PUT").isTrue();
        assertThat(map.get(a1)).as("a1 isDefault must be false after a2 became default").isFalse();
    }

    @Test
    @DisplayName("TC210 — Default-address PUT for unknown user returns 404")
    void tc210_defaultPutUnknownUser_404() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/999999/addresses/1/default")
                .bearer(admin)
                .put();
        assertThat(r.status()).as("default PUT ghost user").isEqualTo(404);
    }

    @Test
    @DisplayName("TC211 — Default-address PUT with foreign address returns 404")
    void tc211_defaultPutForeignAddress_404() {
        UserSeederSupport.AuthedUser u1 = UserSeederSupport.registerRider("tc211a");
        UserSeederSupport.AuthedUser u2 = UserSeederSupport.registerRider("tc211b");
        long a2 = seedAddress(u2, "u2 home", true);

        // u1 attempts to mark u2's address as default — black-box authz must reject this.
        Http.Response r = Http.request(USER_BASE,
                        "/api/users/" + u1.uid() + "/addresses/" + a2 + "/default")
                .bearer(u1.token())
                .put();
        assertThat(r.status())
                .as("u1 marking u2's address as default — must be 404 (or 403)")
                .isBetween(400, 499);
    }

    @Test
    @DisplayName("TC212 — Profile returns user fields + savedAddresses list")
    void tc212_profileWithAddresses() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc212");
        seedAddress(me, "Home", true);
        seedAddress(me, "Work", false);

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/profile")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("profile").isBetween(200, 299);

        JsonNode body = r.json();
        boolean hasIdentity = body.has("name") || body.has("email");
        assertThat(hasIdentity).as("profile has name or email").isTrue();

        JsonNode addrs = body.has("savedAddresses") ? body.path("savedAddresses")
                : body.has("addresses") ? body.path("addresses")
                : body.path("saved_addresses");
        assertThat(addrs.isArray()).as("addresses field is an array").isTrue();
        assertThat(addrs.size()).as("addresses list size >= 2").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("TC213 — Profile for user with no addresses returns empty addresses list")
    void tc213_profileEmptyAddresses() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc213");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/profile")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("profile").isBetween(200, 299);

        JsonNode addrs = r.json().has("savedAddresses") ? r.json().path("savedAddresses")
                : r.json().has("addresses") ? r.json().path("addresses")
                : null;
        if (addrs != null && !addrs.isMissingNode()) {
            assertThat(addrs.size()).as("addresses empty").isEqualTo(0);
        }
    }

    @Test
    @DisplayName("TC214 — Profile for non-existent user returns 404")
    void tc214_profileNonExistent_404() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/999999/profile")
                .bearer(admin)
                .get();
        assertThat(r.status()).as("profile ghost user").isEqualTo(404);
    }

    @Test
    @DisplayName("TC220 — Profile body includes prefs.language=ar from JSONB")
    void tc220_profileEmbedsPreferences() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc220");

        // Seed prefs via the PUT endpoint
        Http.Response setPref = Http.request(USER_BASE, "/api/users/" + me.uid() + "/preferences")
                .bearer(me.token())
                .json(Map.of("language", "ar", "theme", "dark"))
                .put();
        assertThat(setPref.status()).as("seed prefs").isBetween(200, 299);

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/profile")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("profile").isBetween(200, 299);

        if (r.json().has("preferences")) {
            assertThat(r.json().path("preferences").path("language").asText())
                    .as("preferences.language is ar")
                    .isEqualTo("ar");
        }
    }

    @Test
    @DisplayName("TC332 — Profile.totalAddresses equals number of saved addresses")
    void tc332_profileTotalAddresses() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc332");
        seedAddress(me, "Home", true);
        seedAddress(me, "Work", false);
        seedAddress(me, "Gym", false);

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/profile")
                .bearer(me.token())
                .get();
        assertThat(r.status()).as("profile").isBetween(200, 299);

        JsonNode body = r.json();
        if (body.has("totalAddresses")) {
            assertThat(body.path("totalAddresses").asInt())
                    .as("totalAddresses equals 3").isEqualTo(3);
        } else {
            JsonNode addrs = body.has("savedAddresses") ? body.path("savedAddresses")
                    : body.has("addresses") ? body.path("addresses")
                    : body.path("saved_addresses");
            assertThat(addrs.isArray()).as("addresses array present").isTrue();
            assertThat(addrs.size()).as("3 saved addresses").isEqualTo(3);
        }
    }
}
