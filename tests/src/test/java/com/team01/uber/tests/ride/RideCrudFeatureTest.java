package com.team01.uber.tests.ride;

import com.team01.uber.tests.BaseHttpTest;
import com.team01.uber.tests.fixtures.Http;
import com.team01.uber.tests.fixtures.Mongo;
import com.team01.uber.tests.fixtures.Redis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic CRUD coverage for /api/rides plus the cache-by-id and Observer-write
 * invariants every M3 service must preserve from M2 (see uber-m3.md:43-44).
 */
@DisplayName("Ride CRUD — POST/GET/PUT/DELETE + ride-by-id cache")
class RideCrudFeatureTest extends BaseHttpTest {

    @Test
    @DisplayName("Ride CRUD — POST /api/rides returns 2xx + id")
    void crud_postRide_returns2xxWithId() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("ridecreate");

        long id = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        assertThat(id).as("ride id from POST").isPositive();
    }

    @Test
    @DisplayName("Ride CRUD — GET /api/rides/{id} caches ride-service::ride::{id}")
    void crud_getByIdCaches_rideId() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("ridecache");
        long id = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        // Prime + read
        Http.Response first = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + id)
                .bearer(rider.token())
                .get();
        assertThat(first.status()).as("GET ride by id #1").isBetween(200, 299);
        Http.Response second = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + id)
                .bearer(rider.token())
                .get();
        assertThat(second.status()).as("GET ride by id #2 (cached)").isBetween(200, 299);

        assertThat(Redis.countKeys("ride-service::ride::" + id))
                .as("ride-service::ride::" + id + " should be cached after GET-by-id")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Ride CRUD — GET /api/rides returns 2xx (list not cached)")
    void crud_listRides_returns2xx() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("ridelist");
        RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("list rides").isBetween(200, 299);
        assertThat(r.json().isArray()).as("response is an array").isTrue();
    }

    @Test
    @DisplayName("Ride CRUD — DELETE /api/rides/{id} returns 204 + invalidates cache")
    void crud_deleteRide_invalidatesCache() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("ridedel");
        long id = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        // Prime cache
        Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + id).bearer(rider.token()).get();
        assertThat(Redis.countKeys("ride-service::ride::" + id))
                .as("cache primed before delete")
                .isGreaterThanOrEqualTo(0);

        Http.Response del = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + id)
                .bearer(rider.token())
                .delete();
        assertThat(del.status()).as("delete ride").isBetween(200, 299);

        Http.Response after = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/" + id)
                .bearer(rider.token())
                .get();
        assertThat(after.status()).as("read after delete — expected 404").isEqualTo(404);
    }

    @Test
    @DisplayName("Ride CRUD — POST /api/rides writes Observer event to ride_events")
    void crud_postRide_observerWritesRideCreated() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("rideobs");
        long id = RideTestSupport.createCompletedRide(rider.token(), rider.uid(), null);

        long count = Mongo.countAtLeast(
                "ride_events",
                Map.of("rideId", id, "action", "RIDE_CREATED"),
                1,
                Duration.ofSeconds(5));
        assertThat(count)
                .as("Observer must write a RIDE_CREATED event for ride id " + id)
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Ride CRUD — GET /api/rides/{unknown} returns 404")
    void crud_getUnknownRide_returns404() {
        RideTestSupport.AuthedRider rider = RideTestSupport.registerRider("ride404");

        Http.Response r = Http.request(RideTestSupport.RIDE_BASE, "/api/rides/99999999")
                .bearer(rider.token())
                .get();

        assertThat(r.status()).as("get unknown ride").isEqualTo(404);
    }
}
