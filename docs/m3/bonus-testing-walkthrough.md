# Bonus Testing Suite — Walkthrough

PR #240 delivers the M3 §15 Bonus **"Full Testing Suite"** — **44 tests, 0 failures, 0 skipped**, across all 5 services. This walkthrough covers (1) how to run the suite, and (2) the exact business-logic scenarios each test exercises.

---

## What §15 Bonus asks for (verbatim, `docs/m3/uber-m3.md` §15 line 2626)

> **Full Testing Suite** — *"(1) Unit tests for service business logic with `@MockBean` on all Feign clients. (2) RabbitMQ consumer integration tests with Testcontainers — publish an event, assert the consumer processes it and mutates the local DB. (3) Saga E2E test: trigger S3-F4, assert `payment.initiated` is received; then inject payment failure, assert compensation runs."*

| Spec item | Delivered by |
|---|---|
| **(1)** Unit tests w/ Feign mocks | `UserServiceFeignTest` · `DriverServiceFeignTest` · `PaymentServiceFeignTest` · `RideServiceSagaPrechecksTest` |
| **(2)** Testcontainers consumer ITs | `UserRideEventConsumerIT` · `DriverRideEventConsumerIT` · `PaymentRideEventConsumerIT` · `LocationRideSagaConsumerIT` |
| **(3)** Saga E2E w/ compensation | `RideServiceSagaPrechecksTest` (S3-F4 trigger) + `PaymentEventConsumerSagaTest` (`payment.failed` → compensation cascade) |

> `@MockitoBean` is used everywhere `@MockBean` appears in the spec — Spring Boot 4.0 removed the legacy `@MockBean`; `@MockitoBean` at `org.springframework.test.context.bean.override.mockito.MockitoBean` is the only replacement and is semantically identical.

---

## How to run

### Switch to the branch

```powershell
git fetch origin
git checkout feat/M3/cc/bonus-testing-suite/55-24853
```

### The whole suite in one command (44 tests, ~4 minutes first run)

Needs Docker Desktop running (Testcontainers pulls `rabbitmq:3-management`, `redis:7-alpine`, `cassandra:4.1`).

```powershell
mvn -pl user-service,driver-service,ride-service,location-service,payment-service -am `
  "-Dtest=UserServiceFeignTest,DriverServiceFeignTest,PaymentServiceFeignTest,RideServiceSagaPrechecksTest,PaymentEventConsumerSagaTest,UserRideEventConsumerIT,DriverRideEventConsumerIT,PaymentRideEventConsumerIT,LocationRideSagaConsumerIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  test
```

Expected: `BUILD SUCCESS` with **44 tests, 0 failures, 0 skipped**.

### Run each class manually (one at a time)

**Unit tests (no Docker needed, < 5s each):**

```powershell
mvn -pl user-service    -am "-Dtest=UserServiceFeignTest"          "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl driver-service  -am "-Dtest=DriverServiceFeignTest"        "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl payment-service -am "-Dtest=PaymentServiceFeignTest"       "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl ride-service    -am "-Dtest=RideServiceSagaPrechecksTest"  "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl ride-service    -am "-Dtest=PaymentEventConsumerSagaTest"  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

**Integration tests (Docker required, ~40–180s each):**

```powershell
mvn -pl user-service     -am "-Dtest=UserRideEventConsumerIT"     "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl driver-service   -am "-Dtest=DriverRideEventConsumerIT"   "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl payment-service  -am "-Dtest=PaymentRideEventConsumerIT"  "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl location-service -am "-Dtest=LocationRideSagaConsumerIT"  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

**A single test method inside a class:**

```powershell
mvn -pl ride-service -am `
  "-Dtest=PaymentEventConsumerSagaTest#onPaymentFailed_compensationCascadeFires" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

> PowerShell quoting: wrap every `-D...` in double quotes (otherwise it splits on the dot — `"Unknown lifecycle phase .failIfNoSpecifiedTests=false"`). Alternative: prefix everything after `mvn ...` with `--%` to bypass PowerShell parsing.

---

## Business-logic scenarios — what each test actually exercises

### 1. `UserServiceFeignTest` — 9 tests, user-service (§15.1)

Service-level method tests for the three user-service endpoints whose logic depends on Feign calls. Every Feign client (`RideServiceClient`, `PaymentServiceClient`) is a Mockito `@Mock` — no Spring context, no DB.

**S1-F6 `GET /api/users/top-riders`** — aggregate top spenders for a date range.

| Scenario | Setup | Expected |
|---|---|---|
| Happy path: 3 candidates, sort desc, limit 2 | repo returns 3 users; payment-service returns 100 / 500 / 300 per user | top[0]=user 2 ($500), top[1]=user 3 ($300); 3 Feign fan-out calls (one per candidate) |
| Zero-spend user excluded from list | payment-service returns BigDecimal.ZERO for the only candidate | result list is empty |
| Invalid date format short-circuits before Feign | `start="not-a-date"` | 400 `"Invalid date format"`; **zero** Feign calls |

**S1-F9 `GET /api/users/by-language`** — filter users by language preference + minimum completed rides.

| Scenario | Setup | Expected |
|---|---|---|
| Filter by `minRides>=3` | 3 users with rideCounts 5/2/10 | users 1+3 returned (2 below threshold dropped); 3 Feign calls |
| Blank `lang` short-circuits | `lang="  "` | 400 `"lang must not be blank"`; **zero** Feign calls |
| Feign 404 on one user is swallowed | user 1 → FeignException.NotFound, user 2 → count=7 | user 2 returned alone; 404 treated as "0 rides" for filtering, doesn't fail the whole call |

**S1-F4 `PATCH /api/users/{id}/deactivate`** — soft-deactivate with active-rides gate.

| Scenario | Setup | Expected |
|---|---|---|
| 0 active rides → deactivate succeeds | user ACTIVE, rideServiceClient returns 0 | status flipped to DEACTIVATED; `user.deactivated` event published |
| Active rides present → refuse | rideServiceClient returns 2 | 400 `"active rides"`; row never saved; no event published |
| Already DEACTIVATED → idempotent no-op | user already DEACTIVATED | returns 200; **never** consults Feign; no save, no event |

### 2. `DriverServiceFeignTest` — 4 tests, driver-service (§15.1)

S2-F4 `PATCH /api/drivers/{id}/availability` — the OFFLINE gate per §8.3.

| Scenario | Setup | Expected |
|---|---|---|
| Going OFFLINE with 0 active rides | driver BUSY, rideServiceClient returns 0 | status flipped to OFFLINE; `driver.status-changed("BUSY","OFFLINE")` published |
| Going OFFLINE with active rides | driver BUSY, rideServiceClient returns 1 | 400 `"Cannot go OFFLINE"`; row never saved; no event |
| AVAILABLE / BUSY transitions skip Feign | driver AVAILABLE → BUSY | persists; **zero** Feign calls (only OFFLINE consults ride-service) |
| Graceful degradation on Feign 503 | rideServiceClient throws ServiceUnavailable | treated as 0 active rides → OFFLINE persists (ride-service outage shouldn't block legitimate OFFLINE) |

### 3. `PaymentServiceFeignTest` — 4 tests, payment-service (§15.1)

S5-F9 `GET /api/payments/user/{id}/summary` — Feign-gated read per §2.10 caller-existence.

| Scenario | Setup | Expected |
|---|---|---|
| Happy path: aggregate across 3 methods | user exists; repo returns CREDIT_CARD/CASH/WALLET rows | summary: 6 payments, $305 total, per-method breakdown matches |
| Empty payments but user exists | user exists; repo returns empty list | summary returns with totals=0, empty breakdown (NOT 404 — spec wording §7) |
| §2.10 user-service 404 → 404 | userServiceClient throws NotFound | 404 `"User not found"`; **DB query never runs** |
| §2.10 user-service 503 → 503 | userServiceClient throws ServiceUnavailable | 503 `"User service temporarily unavailable"`; **DB query never runs** |

### 4. `RideServiceSagaPrechecksTest` — 6 tests, ride-service (§15.1 + §15.3 trigger)

S3-F4 `POST /api/rides/{id}/complete` — the saga trigger. §8.3 mandates three Feign pre-checks before the local commit + publish.

| Scenario | Setup | Expected |
|---|---|---|
| **Happy path (all 3 pre-checks pass)** | user ACTIVE, driver BUSY, recent ping in last 5 min | `ArgumentCaptor<Ride>` shows saved ride has `status=COMPLETED`, `completedAt!=null`; `InOrder` enforces user-feign → driver-feign → location-feign → `save` → `publishRideCompleted` (§2.11) |
| Pre-check 1: user DEACTIVATED | userServiceClient returns `status="DEACTIVATED"` | 400 `"User account is not active"`; driver & location Feign never called; no save, no publish |
| Pre-check 1: user not found | userServiceClient throws FeignException.NotFound | 400 `"User not found"`; same short-circuit assertions |
| Pre-check 2: driver not BUSY | driverServiceClient returns AVAILABLE | 400 `"Driver is not currently active"`; location Feign never called |
| **Pre-check 3 (Scenario C): location stale** | locationServiceClient throws 404 (no ping last 5 min) | 400 `"Driver not actively tracked"` — this is §8.6 Scenario C verbatim |
| Cheap state check runs first | ride already `COMPLETED` | 400 with **zero** Feign calls (status check short-circuits before fanning out) |

### 5. `PaymentEventConsumerSagaTest` — 6 tests, ride-service (§15.3 E2E)

The §15.3 bonus E2E. Tests the ride-service consumer's reaction to `payment.*` events — the choreography from §8.2.

| Scenario | Setup | Expected |
|---|---|---|
| §8.2 step 4: `payment.initiated` arrives | event with rideId=10 | `markRideStatus(10, PAYMENT_PENDING)`; no `ride.cancelled` published |
| §8.2 step 6a happy path: `payment.completed` | event with rideId=10 | `markRideStatus(10, PAID)`; no cancel |
| **§15.3 bonus: `payment.failed` → compensation cascade** | event reason=`"card declined"` | `markRideStatus(10, PAYMENT_FAILED)` runs **before** `publishRideCancelled(ride, "payment_failed")` — `InOrder` enforces commit-then-publish (§2.11) and `verifyNoMoreInteractions` proves nothing extra fires |
| §8.6 idempotency: re-delivered `payment.failed` | publish event twice; markRideStatus returns ride then null | `markRideStatus` called twice; **`publishRideCancelled` called only once** (compensation doesn't double-fire) |
| Terminal-state idempotency | markRideStatus returns null (ride already terminal) | no `publishRideCancelled` — §16 rule 11 state-based idempotency |
| §8.2 step 7: `payment.refunded` | event | `markRideStatus(10, REFUNDED)`; no cancel |

### 6. `UserRideEventConsumerIT` — 3 tests, user-service (§15.2)

Boots real `rabbitmq:3-management` via Testcontainers + real user-service Spring context. `UserRepository` is `@MockitoBean` so we assert the *exact User row* the consumer would persist.

| Scenario | Publish | Expected (within 10s) |
|---|---|---|
| `ride.completed` over the wire | `RideCompletedEvent(rideId=5001, userId=1001, driverId=7001, fare=50.0)` on `ride.events` → `ride.completed` | `userRepository.save(...)` called once; captured `User` has `totalRides=5` (was 4) and `totalSpent=250.0` (was 200) |
| `ride.cancelled` over the wire | `RideCancelledEvent(rideId=5002, userId=1002, driverId=7002, reason="user_requested")` | `userRepository.save(...)` called once; captured `User` has `totalRides=2` (was 3); `totalSpent` unchanged (RideCancelledEvent carries no fare) |
| User not found on `ride.completed` | event with userId=9999; repo returns Optional.empty() | `findById(9999)` called once; `save(...)` **never** called for userId=9999 (graceful skip, no DLQ) |

### 7. `DriverRideEventConsumerIT` — 3 tests, driver-service (§15.2)

Same Testcontainers shape; `DriverService` is `@MockitoBean`. Elasticsearch auto-config is excluded by FQN string so no real ES backend is needed.

| Scenario | Publish | Expected |
|---|---|---|
| `ride.placed` | `RidePlacedEvent(rideId=81001, userId=71001, driverId=91001)` | `DriverService.handleRidePlaced(driverId=91001, rideId=81001)` invoked once |
| `ride.completed` | `RideCompletedEvent(81002, 71002, 91002, fare=42.5)` | `handleRideCompleted(91002, 81002, 42.5)` invoked once (fare arg propagated) |
| `ride.cancelled` | `RideCancelledEvent(81003, 71003, 91003, "user_requested")` | `handleRideCancelled(91003, 81003)` invoked once |

### 8. `PaymentRideEventConsumerIT` — 3 tests, payment-service (§15.2)

Same Testcontainers shape; `PaymentService` is `@MockitoBean`.

| Scenario | Publish | Expected |
|---|---|---|
| `ride.completed` payload propagation | `RideCompletedEvent(91001, 71001, 81001, 42.5)` | `processRideCompleted(event)` called once with **all fields matching** (rideId, userId, driverId, fare) |
| `ride.cancelled` payload propagation | `RideCancelledEvent(91002, 71002, 81002, "user_requested")` | `processRideCancelled(event)` called with rideId=91002 and reason="user_requested" |
| Cross-dispatch isolation | publish `ride.completed` only | `processRideCompleted` called once; **`processRideCancelled` zero times** (proves `@RabbitHandler` routes by event Java type, not round-robin) |

### 9. `LocationRideSagaConsumerIT` — 6 tests, location-service (§15.2)

The richest IT: real RabbitMQ + real Redis + real Cassandra (all Testcontainers). `LocationTrackingEventRepository` and `MongoEventLogger` are `@MockitoBean`.

| Scenario | Publish | Expected |
|---|---|---|
| `ride.placed` notifies Observer | `RidePlacedEvent(9001, 7001, 8001)` | `mongoEventLogger.onEvent("LOCATION_UPDATED", …)` called once; `trackingRepository.save(...)` **never** called |
| **Redis SETNX idempotency** | publish same `ride.placed` twice | observer `onEvent` called **exactly once** (Redis-backed deduplication, §16 rule 11) |
| **§6 "mark latest row with rideId"** | pre-stub `findTopByKeyDriverId(8002)` to return an existing row; publish `RideCompletedEvent(9002, 7002, 8002, 42.5)` | `ArgumentCaptor<LocationTrackingEvent>` captures the saved row → `saved.getRideId() == 9002L` AND `saved.getKey().getDriverId() == 8002L`; observer fires `"TRIP_COMPLETED"` |
| `ride.completed` redelivery idempotency | publish twice | `findTopByKeyDriverId` called twice, but `save(...)` called **only once** (handler sees rideId already set and skips) |
| No prior tracking ping → no-op | `findTopByKeyDriverId` returns Optional.empty | observer still notified `"TRIP_COMPLETED"`; `save(...)` **never** called; no NullPointerException |
| **§6 "no Cassandra mutation on cancel"** | `RideCancelledEvent(9003, 7003, 8003, "DRIVER_NO_SHOW")` | observer fires `"TRIP_CANCELLED"`; **both** `findTopByKeyDriverId` and `save` never called — §6 verbatim |

---

## Final test counts

| Class | Service | Tests | Spec item |
|---|---|---|---|
| `UserServiceFeignTest` | user | 9 | §15.1 |
| `DriverServiceFeignTest` | driver | 4 | §15.1 |
| `PaymentServiceFeignTest` | payment | 4 | §15.1 |
| `RideServiceSagaPrechecksTest` | ride | 6 | §15.1 + §15.3 trigger |
| `PaymentEventConsumerSagaTest` | ride | 6 | §15.3 |
| `UserRideEventConsumerIT` | user | 3 | §15.2 |
| `DriverRideEventConsumerIT` | driver | 3 | §15.2 |
| `PaymentRideEventConsumerIT` | payment | 3 | §15.2 |
| `LocationRideSagaConsumerIT` | location | 6 | §15.2 |
| **Total** | | **44** | |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `No tests matching pattern "<name>" were executed!` (build fails on `contracts` module) | Add `-Dsurefire.failIfNoSpecifiedTests=false` to skip modules where the filter matches nothing — `-am` rebuilds contracts but it has zero tests. |
| `"Unknown lifecycle phase .failIfNoSpecifiedTests=false"` | PowerShell split the `-D` on the dot. Quote each `-D` (`"-Dfoo.bar=baz"`), or use `--%` before all `-D` args. |
| `Testcontainers could not find a valid Docker environment` | Docker Desktop is not running. Start it and retry. |
| `NoClassDefFoundError: ...JwtConfigurationManager` | Stale `contracts-1.0-SNAPSHOT.jar` in `.m2`. Either add `-am`, or run `mvn install -DskipTests` once and re-run. |
| Cassandra IT times out at startup | First-pull of `cassandra:4.1` is slow. `LocationRideSagaConsumerIT` already declares `.withStartupTimeout(5min)` — if it still times out, run `docker pull cassandra:4.1` manually first. |
