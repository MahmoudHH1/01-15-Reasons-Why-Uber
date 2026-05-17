package com.team01.uber.tests.driver;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Nonce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S2-F11 — Index Driver for Search (Elasticsearch reindex endpoint).
 *
 * <p>Covers TC43..TC47 (5 TCs). See {@code tests/20-driver-service.sh} §10.2.2.
 */
@DisplayName("S2-F11 — Index driver for search (ES)")
class DriverIndexFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC43 — POST /api/drivers/{id}/index for an existing entity returns 2xx")
    void tc43_indexExistingDriver_returns2xx() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc43");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC43 Driver"));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/index")
                .bearer(rider.token())
                .post();

        assertThat(r.status()).as("POST /index existing driver").isBetween(200, 299);
    }

    @Test
    @DisplayName("TC44 — After indexing, ES doc fields match the PG row's attributes")
    void tc44_indexedFields_matchPgRow() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc44");
        String token = rider.token();
        String unique = "tc44name" + Nonce.nonce().substring(0, 8);
        Map<String, Object> body = DriverSeederSupport.driverBody(
                unique, "SEDAN", "AVAILABLE", 4.5, 20, "unique-tc44 vehicle");
        long driverId = DriverSeederSupport.createDriver(token, body);

        DriverSeederSupport.indexDriver(token, driverId);

        Http.Response search = Http.request(DRIVER_BASE,
                "/api/drivers/search/full-text?query=" + unique)
                .bearer(token)
                .get();
        assertThat(search.status()).as("post-index search status").isBetween(200, 299);

        // Fetch the PG row for comparison.
        Http.Response pg = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token)
                .get();
        assertThat(pg.status()).as("PG read status").isBetween(200, 299);
        String pgName = pg.json().path("name").asText();
        String pgStatus = pg.json().path("status").asText();

        List<Long> hits = DriverSeederSupport.extractIds(search);
        assertThat(hits).as("ES doc count for unique query").contains(driverId);

        // Locate the matching item and assert name+status match PG.
        boolean foundMatch = false;
        for (var node : search.json()) {
            if (node.path("id").asLong() == driverId) {
                assertThat(node.path("name").asText())
                        .as("ES name matches PG name")
                        .isEqualTo(pgName);
                String esStatus = node.path("status").asText("");
                if (!esStatus.isBlank()) {
                    assertThat(esStatus).as("ES status matches PG status").isEqualTo(pgStatus);
                }
                foundMatch = true;
                break;
            }
        }
        assertThat(foundMatch).as("matching ES doc located by ID").isTrue();
    }

    @Test
    @DisplayName("TC45 — Updating an entity via PUT (without /index) makes the new name searchable")
    void tc45_putUpdate_autoReindex() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc45");
        String token = rider.token();

        String originalName = "tc45orig" + Nonce.nonce().substring(0, 6);
        Map<String, Object> body = DriverSeederSupport.driverBody(
                originalName, "SEDAN", "AVAILABLE", 4.0, 10, "old desc");
        long driverId = DriverSeederSupport.createDriver(token, body);

        String newName = "tc45renamed" + Nonce.nonce().substring(0, 6);
        Map<String, Object> updated = new java.util.LinkedHashMap<>(body);
        updated.put("name", newName);
        Http.Response putR = Http.request(DRIVER_BASE, "/api/drivers/" + driverId)
                .bearer(token)
                .json(updated)
                .put();
        assertThat(putR.status()).as("PUT update").isBetween(200, 299);

        // Allow ES refresh.
        try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        Http.Response s = Http.request(DRIVER_BASE, "/api/drivers/search/full-text?query=" + newName)
                .bearer(token)
                .get();

        assertThat(s.status()).as("search by new name").isBetween(200, 299);
        boolean found = false;
        for (var node : s.json()) {
            if (newName.equalsIgnoreCase(node.path("name").asText(""))) {
                found = true;
                break;
            }
        }
        assertThat(found).as("PUT auto-reindexes — new name searchable").isTrue();
    }

    @Test
    @DisplayName("TC46 — POST /api/drivers/<Long.MAX_VALUE>/index returns strictly 404")
    void tc46_unknownDriver_returns404() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc46");

        Http.Response r = Http.request(DRIVER_BASE,
                "/api/drivers/" + Long.MAX_VALUE + "/index")
                .bearer(rider.token())
                .post();

        assertThat(r.status()).as("index Long.MAX_VALUE driver").isEqualTo(404);
    }

    @Test
    @DisplayName("TC47 — POST /api/drivers/{id}/index without Authorization header returns 401")
    void tc47_noAuth_returns401() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("tc47");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("TC47 Driver"));

        Http.Response r = Http.request(DRIVER_BASE, "/api/drivers/" + driverId + "/index").post();

        assertThat(r.status()).as("no-auth POST /index").isEqualTo(401);
    }

    @Test
    @DisplayName("S2-F11 supplemental: index emits INDEXED event in driver_events (§10.2.2.e)")
    void s2f11_indexEmitsDriverEvent() {
        DriverSeederSupport.AuthedUser rider = DriverSeederSupport.registerRider("s2f11ev");
        long driverId = DriverSeederSupport.createDriver(rider.token(),
                DriverSeederSupport.driverBody("S2F11 Event"));

        DriverSeederSupport.indexDriver(rider.token(), driverId);

        long observed = Mongo.countAtLeast(
                "driver_events",
                Map.of("driverId", driverId, "action", "INDEXED"),
                1,
                Duration.ofSeconds(8));

        assertThat(observed)
                .as("INDEXED event emitted to driver_events for driverId=" + driverId)
                .isGreaterThanOrEqualTo(1);
    }
}
