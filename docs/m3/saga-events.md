<!-- Loaded by: skills/saga-validator, skills/rabbitmq-bootstrap, skills/m3-orchestrator, skills/pr-check -->

# M3 Choreography Saga — Ride Lifecycle & Cancellation Cascade

Source: `docs/m3/uber-m3.md` §8 (lines 1207–1397). This file extracts the saga topology, payloads, scenarios, and infrastructure deliverables in one place so skills can reference it without re-reading the full spec.

## What is a Choreography Saga (uber-m3.md:1209–1216)

> When a business transaction spans multiple services, there is no distributed rollback. The Choreography Saga achieves eventual consistency through:
>
> 1. **Forward path:** each service listens for the previous step's success event and executes its part.
> 2. **Compensation path:** on failure, the failing service publishes a failure event; every service that already committed reverses its local change on receipt of the compensation event.

For Uber, the saga binds the ride lifecycle to the payment lifecycle: rider/driver completes the ride, payment is settled asynchronously, and a failed settlement reverses every committed side effect.

## Trigger

`PUT /api/rides/{id}/complete` — owned by **S3-F4** (uber-m3.md:1220).

## Pre-saga Feign checks (uber-m3.md:1225–1228)

All three must pass **before any event is published**. Any 404 or wrong status → 400 and saga aborts.

- Feign → user-service: `GET /api/users/{id}` → status must be **ACTIVE**
- Feign → driver-service: `GET /api/drivers/{id}` → status must be **BUSY** (proves driver is currently assigned to this ride)
- Feign → location-service: `GET /api/locations/driver/{driverId}/recent` → returns 200 with latest GPS ping if **≤ 5 minutes old**; 404 → 400 ("driver not actively tracked")

## Forward path

```
[S3] Ride → COMPLETED (saga-status, awaiting payment)
     publishes → ride.completed {rideId, userId, driverId, fare}
                    │
      ┌─────────────┼──────────────┬─────────────────┐
      ▼             ▼              ▼                  ▼
   [S1]          [S2]           [S4]              [S5]
ride.completed ride.completed ride.completed   ride.completed
consumer        consumer        consumer          consumer
      │             │              │                  │
Update rider    Update          Mark final         Create
ride stats      driver: set     location ping      PENDING Payment
(local DB)      AVAILABLE       with rideId        (local DB)
                + bump          (local DB)         publishes →
                earnings        publishes →        payment.initiated
                (local DB)      (optional)
                                location.tracked
                                      │                  │
                                      └──────────────────┘
                                              │
                               [S3 consumes payment.initiated]
                               Ride → PAYMENT_PENDING
```

Then, when rider pays via `POST /api/payments/ride/{rideId}` (uber-m3.md:1258):

```
[S5] processes payment (M2 mock + Strategy from S5-F12)
     publishes → payment.completed  OR  payment.failed

[S3 consumes payment.completed] → Ride → PAID  ✅ SAGA DONE
```

## Compensation path (payment.failed)

```
[S3 consumes payment.failed] → Ride → PAYMENT_FAILED
     publishes → ride.cancelled {rideId, userId, driverId, reason: "payment_failed"}
                    │
      ┌─────────────┼──────────────┬─────────────────┐
      ▼             ▼              ▼                  ▼
   [S1]          [S2]           [S4]              [S5]
ride.cancelled  ride.cancelled ride.cancelled    ride.cancelled
consumer        consumer        consumer          consumer
      │             │              │                  │
Reverse rider   Reverse driver  Log               Refund PENDING
ride stats      stats; set      TRIP_CANCELLED    payment via
(local DB)      driver back to  (Mongo only)      S5-F12 Strategy
                AVAILABLE                         publishes →
                (local DB)                        payment.refunded
                                              [S3 consumes payment.refunded]
                                              Ride → REFUNDED
```

## New Ride statuses (uber-m3.md:46–57)

| Status | When set |
|---|---|
| `COMPLETED` | S3-F4 sets immediately before publishing `ride.completed` (saga "ride finished, awaiting payment") |
| `PAYMENT_PENDING` | S3 consumes `payment.initiated` |
| `PAID` | S3 consumes `payment.completed` |
| `PAYMENT_FAILED` | S3 consumes `payment.failed` |
| `REFUNDED` | S3 consumes `payment.refunded` |

`REQUESTED`, `ACCEPTED`, `IN_PROGRESS`, `CANCELLED` from M1 stay unchanged.

## Cancellation cascade — S3-F7 (uber-m3.md:1321–1348)

`PUT /api/rides/{id}/cancel`. M1 wrote directly to the `drivers` table; M3 publishes `ride.cancelled` instead — driver-service consumes and flips its driver row to AVAILABLE; payment-service consumes and refunds any pre-existing payment.

Behavior:
1. Find ride by ID → 404 if not found.
2. Validate status IN (REQUESTED, ACCEPTED) → 400 if not.
3. Set ride status = CANCELLED.
4. Publish `ride.cancelled` to `ride.events` exchange with payload `{rideId, userId, driverId, reason: "user_requested"}`. If `driverId` is null (cancelled before assignment), payload still contains `driverId=null` — driver-service silently ignores when null.
5. Return 200.

## Saga participant summary (uber-m3.md:1354–1361)

| Service | Feign calls in saga | Publishes | Consumes |
|---|---|---|---|
| user-service | Target of S3 + S5 pre-checks | `user.registered`, `user.deactivated` | `ride.completed`, `ride.cancelled` |
| driver-service | Target of S3 pre-check + S4 enrichment | `driver.status-changed`, `driver.rated`, `driver.document.verified` | `ride.placed`, `ride.completed`, `ride.cancelled` |
| ride-service | → user, → driver, → location (pre-checks) | `ride.placed`, `ride.completed`, `ride.cancelled` | `payment.initiated`, `payment.completed`, `payment.failed`, `payment.refunded` |
| location-service | Target of S3 pre-check; → driver (S4-F3, S4-F9) | `location.tracked` (audit only, optional) | `ride.placed`, `ride.completed`, `ride.cancelled` |
| payment-service | → user (S5-F3), → ride (S5-F4, S5-F10), → driver (S5-F10) | `payment.initiated`, `payment.completed`, `payment.failed`, `payment.refunded` | `ride.completed`, `ride.cancelled` |

## Test scenarios (uber-m3.md:1362–1387)

### Scenario A — Happy path end-to-end

1. (setup) User ID=1 (ACTIVE), Driver ID=5 (BUSY, assigned to Ride ID=10), Ride ID=10 (status=IN_PROGRESS, userId=1, driverId=5, fare=null), Location for driver 5 with timestamp 1 minute ago.
2. (action) `PUT /api/rides/10/complete` → all three pre-checks pass.
3. (expect) 200. Ride status = COMPLETED. `ride.completed` published with `fare` computed.
4. (verify after event processing) Ride status = PAYMENT_PENDING; driver-postgres driver 5 status = AVAILABLE with totalEarnings incremented; payment-postgres has a PENDING `Payment` for rideId=10 with amount = fare.
5. (action) `POST /api/payments/ride/10` body `{"method": "CREDIT_CARD", "cardLastFour": "4242"}`.
6. (expect) 201. `payment.completed` published.
7. (verify after event processing) Ride status = PAID. Poll `GET /api/rides/10` until status changes from PAYMENT_PENDING to PAID, or wait ≥ 1s.

### Scenario B — Payment failure and compensation

1. (setup) Same as Scenario A — reach Ride status = PAYMENT_PENDING with PENDING payment in payment-postgres.
2. (action) `POST /api/payments/ride/10` body `{"method": "BITCOIN"}` (deliberately unsupported method to force failure).
3. (expect) 400. `payment.failed` published.
4. (verify after the compensation cascade runs) The cascade is **5 hops async**: `payment.failed` → S3 sets Ride = PAYMENT_FAILED → S3 publishes `ride.cancelled` (reason=`"payment_failed"`) → S1/S2/S4/S5 consumers reverse their state → S5 issues refund and publishes `payment.refunded` → S3 sets Ride = REFUNDED.
5. **Poll `GET /api/rides/10` until `status = REFUNDED`, or wait ≥ 3s** for the full cascade. Then assert: `ride.cancelled` was published with `reason="payment_failed"`; rider stats reversed; driver stats reversed (driver back to AVAILABLE if still BUSY); payment-postgres Payment row status = REFUNDED; ride-postgres Ride status = REFUNDED.

### Scenario C — Pre-check failure (no recent location ping)

1. (setup) User ID=1 (ACTIVE), Driver ID=5 (BUSY), Ride ID=10 (IN_PROGRESS). Latest location for driver 5 has a timestamp from **30 minutes ago** (stale).
2. (action) `PUT /api/rides/10/complete`.
3. (expect) 400 — location-service `GET /api/locations/driver/5/recent` returns 404 because the latest ping is older than 5 minutes. **S3 aborts before publishing any event.**
4. (verify) **No `ride.completed` event in RabbitMQ.** Ride status still = IN_PROGRESS. Driver stays BUSY.

## Saga infrastructure deliverables (uber-m3.md:1388–1397)

- [ ] `RideEventConfig` in ride-service: `ride.events` TopicExchange
- [ ] `DriverEventConfig` in driver-service: `driver.events` TopicExchange
- [ ] `UserEventConfig` in user-service: `user.events` TopicExchange
- [ ] `LocationEventConfig` in location-service: `location.events` TopicExchange (publisher optional)
- [ ] `PaymentEventConfig` in payment-service: `payment.events` TopicExchange
- [ ] All consumer queue declarations with DLQ (one per service per exchange it listens to)
- [ ] All event payload `record` classes in `contracts/.../events/` (e.g., `RideCompletedEvent`, `PaymentFailedEvent`)
- [ ] Saga test scenarios A, B, C verified end-to-end (assigned to **`S5-INFRA`** owner per uber-m3.md:2522)

## Idempotency rule (uber-m3.md:2645)

> Consumers must be idempotent. RabbitMQ delivery is at-least-once: with `acknowledge-mode: auto` + `default-requeue-rejected: false` + `max-attempts: 3` (Rule 3), a transient listener failure causes Spring to retry the same message before DLQ routing. Consumers therefore see duplicates. Use **state-based idempotency** — check the target row's status before mutating.

Examples:
- `ride.completed` consumer in driver-service that increments earnings: first read the driver row and skip if the rideId has already been counted.
- `payment.completed` consumer in ride-service: check `ride.status != PAID` before transitioning.

State-based idempotency is sufficient for M3 scope; explicit idempotency keys are not required.
