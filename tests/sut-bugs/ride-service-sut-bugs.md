# ride-service — SUT bugs found by the test suite

Each entry maps a failing assertion in `tests/30-ride-service.sh` to a verbatim
spec citation. SUT code lives under `ride-service/src/main/...` and must be
fixed by the ride-service team — the test suite does not modify SUT sources.

## §10.3.1 — `completionRate` returned as percentage (0–100) instead of fraction (0.0–1.0)
**Test:** `tests/30-ride-service.sh:139-147` — `pass "§10.3.1.a completionRate≈$exp_rate (got $cr)"`
**Spec quote:**
> §10.3.1 Test scenario — "a) Create 10 rides in March 2026: 6 COMPLETED, 2 CANCELLED, 2 REQUESTED. GET /api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31 → totalRides=10, **completionRate=0.6**, ridesByStatus={COMPLETED:6, CANCELLED:2, REQUESTED:2}."
> (Uber_descriptionM2.pdf §10.3.1.a, p. 38)

**Observed:** The S3-F10 dashboard computes `completionRate` as `((double) completedRides / totalRides) * 100.0`, returning `60.0` for the spec scenario instead of `0.6`. The test asserts `0.59 ≤ completionRate ≤ 0.61` per the spec's stated value and therefore fails.

**Expected per spec:** `completionRate` must be the raw fraction `completedRides / totalRides` (a value in `[0.0, 1.0]`). Drop the `* 100.0`.

**Likely location:** `ride-service/src/main/java/com/team01/uber/ride/service/RideService.java` line 440-442 inside `getRideAnalyticsDashboard`:
```java
double completionRate = totalRides > 0
        ? ((double) completedRides / totalRides) * 100.0   // remove "* 100.0"
        : 0.0;
```

---

## §7.2 + §10.3.1.d — Mongo URI mis-keyed under `spring.mongodb.uri`; all ride-service Mongo writes silently fail
**Test:** `tests/30-ride-service.sh:170-177` — `pass "S3-F10 ANALYTICS_VIEWED logged on every call (+$diff)"`
**Spec quote:**
> §10.3.1 Behavior — "d) **Log an ANALYTICS_VIEWED event to the ride_events collection in MongoDB.** This log must be written on every invocation, independently of whether the response was served from cache — perform the logging step outside the cache decorator/layer so it runs on cache hits too."
> (Uber_descriptionM2.pdf §10.3.1.d, p. 38)

**Observed:** `ride_events` collection in MongoDB never grows after dashboard calls (`+0` instead of `+2`). The controller's `rideService.logDashboardViewed(...)` call runs and the observer chain fires `notifyObservers("ANALYTICS_VIEWED", payload)`, but `MongoEventLogger.onEvent` swallows the resulting exception and `log.warn`s. Root cause: `application.yml` declares the Mongo URI under `spring.mongodb.uri` (line 20-21), but Spring Boot's MongoDB auto-configuration reads `spring.data.mongodb.uri`. With the correct property unset, the driver falls back to `mongodb://localhost:27017/test` which from inside the `ride-service` container resolves to itself (no Mongo there) and every save throws.

```yaml
# ride-service/src/main/resources/application.yml — current (broken)
spring:
  mongodb:
    uri: mongodb://root:rootpass@mongo:27017/ubermongo?authSource=admin
  data:
    redis: ...
    neo4j: ...
```

The four sibling services (`user-service`, `driver-service`, `location-service`, `payment-service`) all correctly nest the URI under `spring.data.mongodb.uri`.

**Expected per spec:** Every M2 service writes `*_events` documents to MongoDB on observer notifications. With the URI mis-keyed, NO ride event (RIDE_CREATED, RIDE_COMPLETED, ANALYTICS_VIEWED, INTERACTION_RECORDED, …) ever lands in Mongo. Auto-grader will see an empty `ride_events` collection across the entire suite.

**Likely location:** `ride-service/src/main/resources/application.yml` — move lines 20-21 under `spring.data` so the path becomes `spring.data.mongodb.uri`.

---

## §10.3.2 — `POST /api/rides/{rideId}/record-interaction` endpoint not implemented
**Test:** `tests/30-ride-service.sh:198-230` — five assertions covering steps a–f of §10.3.2 (first record on R1, INTERACTION_RECORDED event, idempotent re-call, second distinct rideId, REQUESTED→400, unknown→404, no-token→401).
**Spec quote:**
> §10.3.2 Endpoint: **POST /api/rides/{rideId}/record-interaction** … "h) Return status code 200 with a confirmation message."
> (Uber_descriptionM2.pdf §10.3.2, p. 39)

**Observed:** Every call to `POST /api/rides/{rideId}/record-interaction` returns 404 with `path=/api/rides/83/record-interaction` — Spring's resource-not-found page, because no controller method maps that path. The Neo4j scaffolding (`UserNode`, `DriverNode`, `RodeWithRelationship`, `Neo4jRecordAdapter`) is in place under `ride-service/src/main/java/com/team01/uber/ride/model/` and `.../adapter/` but no service/controller wiring uses it. Per the spec, the endpoint must:
1. 401 on missing JWT.
2. 404 if the ride doesn't exist in PG.
3. 400 if the ride's status is not COMPLETED (the test verifies this on a REQUESTED ride — currently returns 404 because the route itself is missing).
4. Idempotent on `rideId` via a Neo4j-only marker (recorded_ride_ids set on RODE_WITH, or `(:User)-[:RECORDED_RIDE {rideId}]->(:Driver)`).
5. Emit `INTERACTION_RECORDED` to `ride_events` only on the non-idempotent path.
6. Wildcard-invalidate `ride-service::S3-F12::*` on the non-idempotent path.

**Expected per spec:** Working `POST /api/rides/{rideId}/record-interaction` per §10.3.2 steps a–h.

**Likely location:** `ride-service/src/main/java/com/team01/uber/ride/controller/RideController.java` (add `@PostMapping("/{rideId}/record-interaction")`), a new `RideInteractionService` method that orchestrates PG validation + Neo4j MERGE + observer emit, and a new Neo4j repository method on `DriverNodeRepository`/`UserNodeRepository`. There is an open in-flight branch `feat/ride/S3-F11/55-13512` that has not yet merged to `main`.

---

## §10.3.3 — `GET /api/rides/recommendations` endpoint not implemented (collides with `/api/rides/{id}`)
**Test:** `tests/30-ride-service.sh:246-325` — `assert_status 200 "S3-F12 own recommendations → 200"`, `assert_status 403 "S3-F12 cross-user → 403"`, `assert_status 200 "S3-F12 default limit"` and surrounding §10.3.3 cases.
**Spec quote:**
> §10.3.3 Endpoint: **GET /api/rides/recommendations?userId={id}&limit={n}** — "b) Ownership check: Verify the authenticated caller's uid claim from the JWT equals the userId query parameter, OR the caller's role claim is ADMIN — throws 403 if the caller is neither the target user nor an ADMIN. … h) Return the recommendations with status code 200."
> (Uber_descriptionM2.pdf §10.3.3, p. 40)

**Observed:** Every call returns HTTP 400 (not 404), because `GET /api/rides/recommendations` is being intercepted by `@GetMapping("/{id}") public Ride getRideById(@PathVariable Long id)` in `RideController.java` line 41. Spring tries to coerce the path segment `recommendations` into a `Long`, which raises `MethodArgumentTypeMismatchException` and Spring's default error handler maps it to 400. The test thus fails on three S3-F12 cases: own-recommendations expects 200, cross-user expects 403 (ownership), default-limit expects 200.

**Expected per spec:** A working `GET /api/rides/recommendations` endpoint per §10.3.3 steps a–h, including:
1. JWT validation (401).
2. Ownership check via the `uid` claim (403 on mismatch — direct numeric equality, no PG lookup, per §5 and CLAUDE.md "Ownership-check pattern").
3. Default `limit=5`.
4. Neo4j graph traversal returning `List<DriverRecommendationDTO>` (DTO already exists at `ride-service/src/main/java/com/team01/uber/ride/dto/DriverRecommendationDTO.java`).
5. 5-minute Redis cache under `ride-service::S3-F12::*`.

**Likely location:** `ride-service/src/main/java/com/team01/uber/ride/controller/RideController.java` (add `@GetMapping("/recommendations")` with `@RequestParam Long userId, @RequestParam(defaultValue = "5") int limit`), a new `RecommendationService`, and a Neo4j repository method (`@Query("MATCH ...")`) using the existing `RodeWithRelationship` edge. No `feat/ride/S3-F12/...` branch exists yet on the remote.

---

## §4.4.2 — `GET /api/rides/{rideId}/stops/{stopId}` not cached
**Test:** `tests/30-ride-service.sh:438-442` — `fail "GET-by-id caches ride-service::rideStop::$SID"`
**Spec quote:**
> §4.4.2 CRUD Baseline Endpoints That Must Be Cached — "Uber entities (10): user, saved-address, driver, driver-document, ride, **ride-stop**, location, payment, coupon, payment-coupon. **10 GET-by-ID endpoints must be cached.**"
> (Uber_descriptionM2.pdf §4.4.2, p. 16)
> §8.1 — Entity detail views: 15 minutes.

**Observed:** After `POST /api/rides/{rideId}/stops` followed by `GET /api/rides/{rideId}/stops/{stopId}`, the Redis key `ride-service::rideStop::{stopId}` does not exist. The list and CRUD GET endpoints respond with 200 (so the route works), but `@Cacheable` is missing on the `getRideStopById` service method. The companion entity-detail key for `ride-service::ride::{rideId}` IS populated correctly — only ride-stop is missing.

**Expected per spec:** `GET /api/rides/{rideId}/stops/{stopId}` must populate `ride-service::rideStop::{stopId}` with TTL 15 min. PUT/DELETE on the same `{stopId}` must clear it (the test confirms PUT-invalidation works once the cache is populated, so the wildcard delete plumbing is already wired — only the `@Cacheable` write side is missing).

**Likely location:** `ride-service/src/main/java/com/team01/uber/ride/service/RideStopService.java` — annotate the `getRideStopById(Long rideId, Long stopId)` method with `@Cacheable(value = "ride-service::rideStop", key = "#stopId")` (note: key on `stopId` only, not the composite `rideId:stopId` — see the matching driver-document fix above for the same shape rule). After this single annotation, the existing wildcard-invalidation in `RideStopService` updateRideStop / deleteRideStop will work end-to-end.
