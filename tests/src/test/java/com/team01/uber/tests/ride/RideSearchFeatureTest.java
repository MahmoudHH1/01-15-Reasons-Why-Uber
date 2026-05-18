package com.team01.uber.tests.ride;

import com.fasterxml.jackson.databind.JsonNode;
import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-F1 — Ride search by status/date range. Covers M1-regression TCs that
 * verify filter semantics, empty-filter behaviour, and validation paths.
 */
@DisplayName("S3-F1 — Ride search /api/rides/search")
class RideSearchFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("TC249 — Search ?status=COMPLETED returns COMPLETED rides only")
    void tc249_searchByCompletedStatus_returnsCompletedOnly() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc249");
        long completedId = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);
        long requestedId = RideTestSupport.createRequestedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/search?status=COMPLETED")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("search by COMPLETED").isBetween(200, 299);

        boolean sawCompleted = false;
        boolean sawRequested = false;
        for (JsonNode n : r.json()) {
            long id = n.path("id").asLong();
            String status = n.path("status").asText();
            if (id == completedId) sawCompleted = true;
            if (id == requestedId) sawRequested = true;
            // Every ride in the filtered result must be COMPLETED
            assertThat(status)
                    .as("search ?status=COMPLETED returned ride id=%d with status=%s", id, status)
                    .isEqualTo("COMPLETED");
        }
        assertThat(sawCompleted)
                .as("seeded COMPLETED ride " + completedId + " must appear in status=COMPLETED filter")
                .isTrue();
        assertThat(sawRequested)
                .as("seeded REQUESTED ride " + requestedId + " must NOT appear in status=COMPLETED filter")
                .isFalse();
    }

    @Test
    @DisplayName("TC250 — Search ?startDate&endDate filters by ride date")
    void tc250_searchByDateRange_filtersByDate() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc250");
        long rideId = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        // Wide today-bracket window must include the freshly-seeded ride.
        LocalDate today = LocalDate.now();
        Http.Response in = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/search?startDate=" + today.minusDays(1)
                        + "&endDate=" + today.plusDays(1))
                .bearer(rider.token())
                .get();
        assertThat(in.status()).as("search in-range").isBetween(200, 299);
        boolean foundInRange = false;
        for (JsonNode n : in.json()) {
            if (n.path("id").asLong() == rideId) { foundInRange = true; break; }
        }
        assertThat(foundInRange)
                .as("ride id " + rideId + " falls inside today±1 — must appear")
                .isTrue();
    }

    @Test
    @DisplayName("TC268 — Search returns empty list when filter excludes all rides")
    void tc268_searchExcludesAll_returnsEmpty() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc268");
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        // Far-future range — no rides can fall here.
        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/search?startDate=2099-01-01&endDate=2099-01-31")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("search empty range").isBetween(200, 299);
        assertThat(r.json().isArray()).as("response is array").isTrue();
        assertThat(r.json().size()).as("empty filter must return []").isZero();
    }

    @Test
    @DisplayName("TC342 — Ride search returns rides for the authenticated rider scope")
    void tc342_searchByUserId_filtersByRider() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc342");
        long rideId = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/search?userId=" + rider.uid())
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("search by userId").isBetween(200, 299);
        boolean found = false;
        for (JsonNode n : r.json()) {
            assertThat(n.path("userId").asLong())
                    .as("every ride in userId=%d scope must belong to that rider", rider.uid())
                    .isEqualTo(rider.uid());
            if (n.path("id").asLong() == rideId) found = true;
        }
        assertThat(found).as("seeded ride must appear in own rider scope").isTrue();
    }

    @Test
    @DisplayName("TC350 — Search with malformed startDate returns 400")
    void tc350_malformedStartDate_returns400() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("tc350");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/search?startDate=not-a-date")
                .bearer(rider.token())
                .get();

        assertThat(r.status())
                .as("malformed startDate — expected 400")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("S3-F1 — search results cache to ride-service::S3-F1::*")
    void search_cachesUnderS3F1Namespace() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("s3f1cache");
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE,
                "/api/rides/search?status=COMPLETED&startDate=2026-01-01&endDate=2026-12-31")
                .bearer(rider.token())
                .get();
        assertThat(r.status()).as("search call").isBetween(200, 299);

        assertThat(Redis.countKeys("ride-service::S3-F1::*"))
                .as("S3-F1 search results must populate ride-service::S3-F1::*")
                .isGreaterThanOrEqualTo(1);
    }
}
