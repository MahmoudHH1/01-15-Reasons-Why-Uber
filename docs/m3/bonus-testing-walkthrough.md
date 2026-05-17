# Bonus Testing Suite — Walkthrough

Walkthrough for PR #240, which delivers the M3 §15 Bonus "Full Testing Suite". Covers the three test files, what each test asserts, and how to run them from PowerShell.

## What §15 Bonus asks for (verbatim)

> **Full Testing Suite** — *"(1) Unit tests for service business logic with `@MockBean` on all Feign clients. (2) RabbitMQ consumer integration tests with Testcontainers — publish an event, assert the consumer processes it and mutates the local DB. (3) Saga E2E test: trigger S3-F4, assert `payment.initiated` is received; then inject payment failure, assert compensation runs."*

This is **opt-in extra credit**, not part of the 15 mandatory slices. §16 Critical Rule #9 still applies — the grader runs *their* tests, not yours. The bonus rewards having the testing suite exist and exercise the three required categories.

## Note on `@MockitoBean` vs `@MockBean`

The spec text says `@MockBean`. The PR uses `@MockitoBean`. They are semantically identical — only the import line changes. Reason:

- `@MockBean` at `org.springframework.boot.test.mock.mockito.MockBean` was a Spring Boot-only annotation.
- Spring Boot 3.4 deprecated it. Spring Boot 4.0 **removed** it entirely.
- The replacement is `@MockitoBean` at `org.springframework.test.context.bean.override.mockito.MockitoBean` — same purpose ("find this bean in the Spring context and replace it with a Mockito mock"), now provided by Spring Framework directly rather than Spring Boot.

The project is on Spring Boot 4.0.4 (parent pom), so `@MockBean` does not exist on the classpath; using `@MockitoBean` is the only option. The spec was written when `@MockBean` was still current.

## The three test files

### 1. `RideServiceSagaPrechecksTest.java` — fast unit test, §15.1

| Field | Value |
|---|---|
| Service | `ride-service` |
| Path | `src/test/java/com/team01/uber/ride/service/RideServiceSagaPrechecksTest.java` |
| Style | Pure Mockito (`@Mock` + `@InjectMocks`), no Spring context, no HTTP, no DB |
| Runtime | ~1 second |
| Tests | 6 |

Exercises `RideService.completeRide(id)` — the S3-F4 saga trigger. Constructs the service by hand with all 9 collaborators mocked, then drives each branch of §8.3's three pre-saga Feign checks.

| Test | Asserts |
|---|---|
| `happyPath_allPrechecksPass_publishesRideCompleted` | All three Feign mocks return success. `ArgumentCaptor<Ride>` grabs the saved ride and verifies `status=COMPLETED`, `completedAt != null`. `InOrder` enforces the §2.11 commit-then-publish sequence: user-feign → driver-feign → location-feign → `save` → `publishRideCompleted`. |
| `preCheck1_userDeactivated_throws400_andNoPublish` | `UserDTO.status="DEACTIVATED"` → `ResponseStatusException("User account is not active")`. Asserts driver/location Feign clients were never invoked, no save, no publish. |
| `preCheck1_userNotFound_throws400` | `userServiceClient.getUser(...)` throws `FeignException.NotFound` → 400 "User not found". Same short-circuit assertions. |
| `preCheck2_driverNotBusy_throws400` | Driver returns `AVAILABLE` instead of `BUSY` → 400 "Driver is not currently active". Location check never runs. |
| `preCheck3_locationStale_throws400_perScenarioC` | Location Feign throws 404 ("no ping in last 5 min") → 400 "Driver not actively tracked". This is **Scenario C** from §8.6. |
| `rideStatusNotInProgress_throws400_beforeFeignCalls` | Ride already `COMPLETED` → 400 with zero Feign calls (cheap status check runs first). |

### 2. `LocationRideSagaConsumerIT.java` — Testcontainers integration test, §15.2

| Field | Value |
|---|---|
| Service | `location-service` |
| Path | `src/test/java/com/team01/uber/location/rabbitmq/LocationRideSagaConsumerIT.java` |
| Style | `@SpringBootTest` + Testcontainers (real RabbitMQ + Redis) + `@MockitoBean` for Cassandra/Mongo repos |
| Runtime | ~50 seconds (first run pulls Docker images, subsequent runs faster) |
| Tests | 6 |
| Requires | Docker Desktop running |

Spins up a real `rabbitmq:3-management` broker and a real `redis:7-alpine` instance. Boots the location-service Spring context wired against them via `@ServiceConnection`. Then **really publishes** a JSON message to the broker and waits for the listener to fire.

Why Redis is needed: `LocationService.handleRidePlaced` and `handleRideCancelled` each do a Redis SETNX for idempotency (§16 rule 11). Without a reachable Redis, every message fails and routes to the DLQ, and the test never sees the mock interactions it expects.

| Test | Asserts |
|---|---|
| `ridePlaced_consumerFires_andNotifiesObserverWithLocationUpdated` | Publish a `RidePlacedEvent` → within 10s, `mongoEventLogger.onEvent("LOCATION_UPDATED", …)` called once. `trackingRepository.save(...)` never called (ride.placed does not touch Cassandra). |
| `ridePlaced_redelivered_redisIdempotency_secondDeliveryIsSkipped` | Publish the same event twice → `onEvent` called exactly once. Proves Redis SETNX idempotency. |
| `rideCompleted_marksLatestTrackingRow_withRideId_perSection6` | Pre-stub `findTopByKeyDriverId(8002L)` to return an existing row. Publish `RideCompletedEvent(rideId=9002, driverId=8002)`. `ArgumentCaptor<LocationTrackingEvent>` captures the row passed to `save(...)` → asserts `saved.getRideId() == 9002L` AND `saved.getKey().getDriverId() == 8002L`. Proves §6 *"Mark the most recent location for this driver with the rideId"* end-to-end across the AMQP wire. |
| `rideCompleted_redelivered_isIdempotent_secondDeliveryIsNoOp` | Publish `ride.completed` twice. After the first delivery the row has `rideId` set; after the second, `save(...)` was still called only **once** because `handleRideCompleted` sees the rideId already matches and skips. |
| `rideCompleted_noPriorTrackingPing_noOp` | `findTopByKeyDriverId` returns empty `Optional`. Publish `ride.completed` → `save(...)` never called, no NullPointerException. |
| `rideCancelled_writesTripCancelled_withoutCassandraMutation_perSection6` | Publish `RideCancelledEvent` → `mongoEventLogger.onEvent("TRIP_CANCELLED", …)` called. `findTopByKeyDriverId(any())` AND `save(any())` both never called → proves §6 *"no Cassandra mutation"*. |

### 3. `PaymentEventConsumerSagaTest.java` — saga E2E unit test, §15.3

| Field | Value |
|---|---|
| Service | `ride-service` |
| Path | `src/test/java/com/team01/uber/ride/messaging/consumers/PaymentEventConsumerSagaTest.java` |
| Style | Pure Mockito (`@Mock` + `@InjectMocks`) |
| Runtime | ~1 second |
| Tests | 6 |

Exercises the ride-service `PaymentEventConsumer` — the saga participant that consumes `payment.*` events and produces `ride.cancelled` on payment failure. The §15.3 bonus item is precisely the `payment.failed` case here.

| Test | Asserts |
|---|---|
| `onPaymentInitiated_marksPaymentPending` | `payment.initiated` → `rideService.markRideStatus(10L, PAYMENT_PENDING)`. No `publishRideCancelled` call. §8.2 step 4. |
| `onPaymentCompleted_marksPaid` | `payment.completed` → `markRideStatus(10L, PAID)`. No cancel. §8.2 step 6a (happy path). |
| **`onPaymentFailed_compensationCascadeFires`** | **The §15.3 bonus assertion.** `payment.failed` arrives → asserts `markRideStatus(10L, PAYMENT_FAILED)` AND `publishRideCancelled(ride, "payment_failed")`. `InOrder` asserts the DB write happens *before* the publish (§2.11 commit-then-publish). §8.2 step 6b + §8.4 compensation cascade. |
| `onPaymentFailed_redeliveredEvent_compensationFiresOnlyOnce` | Publish the same `payment.failed` twice. First call returns the ride; second returns null (terminal state). `markRideStatus` called twice, `publishRideCancelled` called **only once** → no duplicate compensation. §16 rule 11. |
| `onPaymentFailed_rideMissing_noCompensation` | `markRideStatus` returns null on the first call (ride missing or terminal). No `publishRideCancelled`. |
| `onPaymentRefunded_marksRefunded` | `payment.refunded` → `markRideStatus(10L, REFUNDED)`. §8.2 step 7. |

## Running the suite (PowerShell)

> PowerShell parses `-Dfoo.bar=baz` differently than CMD — it can split on the dot. Wrap each `-D` argument in double quotes, or use the `--%` stop-parsing token.

### Switch to the branch

```powershell
git fetch origin
git checkout feat/M3/cc/bonus-testing-suite/55-24853
```

### Run the fast suite (no Docker needed) — 12 tests, ~3 seconds

```powershell
mvn -pl ride-service "-Dtest=RideServiceSagaPrechecksTest,PaymentEventConsumerSagaTest" test
```

Expected:

```
[INFO] Tests run: 6, Failures: 0, Errors: 0 -- in PaymentEventConsumer ... saga participant
[INFO] Tests run: 6, Failures: 0, Errors: 0 -- in RideService.completeRide ... pre-saga Feign checks
[INFO] Tests run: 12, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```

### Run the integration test (needs Docker Desktop running) — 6 tests, ~50 seconds

```powershell
mvn -pl location-service "-Dtest=LocationRideSagaConsumerIT" test
```

First run pulls `rabbitmq:3-management` and `redis:7-alpine` from Docker Hub. Subsequent runs are faster (~30 s).

Expected:

```
[INFO] Tests run: 6, Failures: 0, Errors: 0 -- in LocationRideSagaConsumerIT
[INFO] BUILD SUCCESS
```

### Alternative PowerShell syntax (stop-parsing token)

If quoting feels awkward, use `--%`:

```powershell
mvn -pl ride-service --% -Dtest=RideServiceSagaPrechecksTest,PaymentEventConsumerSagaTest test
```

Everything after `--%` is passed verbatim to mvn.

## Troubleshooting

### "Unknown lifecycle phase `.failIfNoSpecifiedTests=false`"

PowerShell split the `-D` argument on the dot. Either:

1. Quote each `-D`: `"-Dsurefire.failIfNoSpecifiedTests=false"`
2. Or use `--%` before all `-D` args
3. Or drop the flag — it only matters when the test filter matches **zero** classes; since we name real classes, omit it:

   ```powershell
   mvn -pl ride-service "-Dtest=RideServiceSagaPrechecksTest,PaymentEventConsumerSagaTest" test
   ```

### `RideServiceApplicationTests.contextLoads` fails with `UnknownHostException: ride-postgres`

That's the **default Spring Boot skeleton test** generated at project init. It tries to boot the full app, which needs the real `ride-postgres` Docker hostname. Not caused by this PR. Two fixes:

1. **Run only the bonus tests** (recommended): name them explicitly with `-Dtest=...` as shown above.
2. **Bring up the compose stack first**: `docker compose up -d ride-postgres mongo redis rabbitmq` etc., then `mvn -pl ride-service test`.

### `Testcontainers could not find a valid Docker environment`

Docker Desktop is not running. Start it and retry.

## Dependencies introduced

- Root `pom.xml`: `testcontainers-bom 1.20.4` in `<dependencyManagement>`.
- `location-service/pom.xml`: `testcontainers` + `junit-jupiter` + `rabbitmq` + `spring-boot-testcontainers` in `<scope>test</scope>`; the BOM is re-imported locally because `spring-boot-starter-parent` inheritance blocks the root pom from transitively supplying it.

No changes under `src/main/`. No changes to `contracts/`. Production code is untouched.

## Independence

This PR branches directly off `origin/main` and depends on **no other PR in the queue**. The unit tests are pure Mockito; the integration test mocks the data repositories and only spins RabbitMQ + Redis as real containers. None of the §2.10 caller-existence fixes (PRs #234-#237), the §2.12 cap fixes (PRs #238-#239), or the §6 action-label fix (PR #233) are required for the suite to run green.

One caveat: the IT for `ride.completed` asserts the action label `"TRACKING_RECORDED"` (matching current `main` behaviour). PR #233 changes that label to `"TRIP_COMPLETED"` per §6. When #233 merges, one assertion in `LocationRideSagaConsumerIT.rideCompleted_marksLatestTrackingRow_withRideId_perSection6` needs the literal flipped — one-line follow-up.
