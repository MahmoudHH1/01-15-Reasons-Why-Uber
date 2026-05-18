package com.team01.uber.tests.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F5 — GET /api/rides/metadata/search?key=…&value=….
 *
 * <p>Exact-match search over the rides.metadata JSONB column.
 */
@DisplayName("S3-F5 — Ride metadata search")
class RideMetadataSearchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC258 — Metadata search ?key=tag&value=premium matches rides with tag=premium")
    void tc258_metadataSearchByPremium_returnsPremiumOnly() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc258");
        // Use a unique tag value so concurrent agent runs don't collide on the
        // JSONB exact-match (Nonce ensures uniqueness across the cluster).
        String premiumValue = "premium_" + Nonce.nonce();
        String basicValue = "basic_" + Nonce.nonce();
        long r1 = RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 100.0, Map.of("tag", premiumValue));
        long r2 = RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 100.0, Map.of("tag", basicValue));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/metadata/search?key=tag&value=" + premiumValue)
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("metadata search happy path").isBetween(200, 299);
        boolean sawPremium = false, sawBasic = false;
        for (JsonNode node : r.json()) {
            long id = node.path("id").asLong();
            if (id == r1) sawPremium = true;
            if (id == r2) sawBasic = true;
        }
        assertThat(sawPremium).as("premium ride " + r1 + " must appear").isTrue();
        assertThat(sawBasic).as("basic ride " + r2 + " must be filtered out").isFalse();
    }

    @Test
    @DisplayName("TC259 — Metadata search with blank key returns 400")
    void tc259_blankKey_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc259");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/metadata/search?key=&value=premium")
                .bearer(rider.token())
                .get();

        assertThat(r.status())
                .as("blank key — must reject with 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("TC273 — Metadata search with unknown value returns empty list")
    void tc273_unknownValue_returnsEmptyList() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc273");
        String premiumValue = "premium_" + Nonce.nonce();
        RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 100.0, Map.of("tag", premiumValue));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/metadata/search?key=tag&value=zzz_" + Nonce.nonce())
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("unknown value").isBetween(200, 299);
        assertThat(r.json().isArray()).as("response is array").isTrue();
        assertThat(r.json().size()).as("unknown value must return []").isZero();
    }

    @Test
    @DisplayName("TC373 — Metadata search response items include the matched ride id")
    void tc373_responseItems_includeRideId() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc373");
        String featureValue = "PREMIUM_" + Nonce.nonce();
        long target = RideTestSupport.createRide(rider.token(), rider.uid(), null,
                "COMPLETED", 100.0, Map.of("feature", featureValue));

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/metadata/search?key=feature&value=" + featureValue)
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("metadata search").isBetween(200, 299);
        boolean found = false;
        for (JsonNode node : r.json()) {
            if (node.path("id").asLong() == target) { found = true; break; }
        }
        assertThat(found)
                .as("response items must carry an id field — target " + target + " not found")
                .isTrue();
    }
}
