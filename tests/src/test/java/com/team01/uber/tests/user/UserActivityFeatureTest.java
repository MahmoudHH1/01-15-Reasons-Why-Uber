package com.team01.uber.tests.user;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S1-F12 — Activity feed")
class UserActivityFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC24 — S1-F12 owner GET own activity returns 2xx with paginated envelope (happy path)")
    void tc24_ownerGetOwnActivity_envelope() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc24");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/activity")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("activity status").isBetween(200, 299);
        assertThat(r.json().has("content")).as("envelope.content").isTrue();
        assertThat(r.json().path("content").isArray()).as("content is array").isTrue();
        assertThat(r.json().has("page")).as("envelope.page").isTrue();
        assertThat(r.json().has("size")).as("envelope.size").isTrue();
        assertThat(r.json().has("totalElements")).as("envelope.totalElements").isTrue();
    }

    @Test
    @DisplayName("TC25 — S1-F12 GET activity for non-existent user ID returns 404 (admin token)")
    void tc25_nonExistentUserActivity_404() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/9223372036854775807/activity")
                .bearer(admin)
                .get();

        assertThat(r.status()).as("activity for Long.MAX_VALUE").isEqualTo(404);
    }

    @Test
    @DisplayName("TC26 — S1-F12 GET activity for negative user ID returns 4xx (admin token)")
    void tc26_negativeUserActivity_4xx() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/-1/activity")
                .bearer(admin)
                .get();

        assertThat(r.status()).as("activity for negative id").isBetween(400, 499);
    }

    @Test
    @DisplayName("TC27 — S1-F12 GET activity for non-numeric user ID returns 4xx (admin token)")
    void tc27_nonNumericUserActivity_4xx() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        Http.Response r = Http.request(USER_BASE, "/api/users/abc/activity")
                .bearer(admin)
                .get();

        assertThat(r.status()).as("activity for non-numeric id").isBetween(400, 499);
    }

    @Test
    @DisplayName("TC28 — S1-F12 size=0 must NOT 5xx (graceful handling)")
    void tc28_sizeZero_notFiveXx() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc28");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/activity?size=0")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("size=0 — must not be 5xx").isLessThan(500);
    }

    @Test
    @DisplayName("TC29 — S1-F12 size=-1 returns 4xx")
    void tc29_sizeNeg_4xx() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc29");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/activity?size=-1")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("size=-1").isBetween(400, 499);
    }

    @Test
    @DisplayName("TC30 — S1-F12 size=string returns 4xx")
    void tc30_sizeString_4xx() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc30");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/activity?size=abc")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("size=abc").isBetween(400, 499);
    }

    @Test
    @DisplayName("TC31 — S1-F12 cross-user activity (regular user A reads User B) returns strictly 403")
    void tc31_crossUserActivity_strict403() {
        UserSeederSupport.AuthedUser alice = UserSeederSupport.registerRider("tc31a");
        UserSeederSupport.AuthedUser bob   = UserSeederSupport.registerRider("tc31b");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + bob.uid() + "/activity")
                .bearer(alice.token())
                .get();

        assertThat(r.status()).as("cross-user activity must be strictly 403").isEqualTo(403);
    }

    @Test
    @DisplayName("TC32 — S1-F12 admin reads any user's activity returns 2xx")
    void tc32_adminReadsActivity_2xx() {
        String admin = UserSeederSupport.adminToken();
        Assumptions.assumeTrue(admin != null, "admin user not seeded — skipping");

        UserSeederSupport.AuthedUser customer = UserSeederSupport.registerRider("tc32cust");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + customer.uid() + "/activity")
                .bearer(admin)
                .get();

        assertThat(r.status()).as("admin reads customer activity").isBetween(200, 299);
        assertThat(r.json().has("content")).as("envelope.content present").isTrue();
        assertThat(r.json().path("content").isArray()).as("content is array").isTrue();
    }

    @Test
    @DisplayName("TC33 — S1-F12 page=-1 returns 4xx")
    void tc33_pageNeg_4xx() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc33");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/activity?page=-1")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("page=-1").isBetween(400, 499);
    }

    @Test
    @DisplayName("TC34 — S1-F12 page=string returns 4xx")
    void tc34_pageString_4xx() {
        UserSeederSupport.AuthedUser me = UserSeederSupport.registerRider("tc34");

        Http.Response r = Http.request(USER_BASE, "/api/users/" + me.uid() + "/activity?page=abc")
                .bearer(me.token())
                .get();

        assertThat(r.status()).as("page=abc").isBetween(400, 499);
    }
}
