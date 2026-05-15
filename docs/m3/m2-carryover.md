<!-- Loaded by: skills/pr-check, skills/m3-orchestrator -->

# M2 Carry-Over Behaviors (Still Graded in M3)

Per `docs/m3/uber-m3.md` lines 38–44:

> All 45 M1 features — except the cross-service SQL inside 17 of them
> All 7 M2 design patterns
> All 6 M2 databases (PostgreSQL + MongoDB + Redis + Elasticsearch + Neo4j + Cassandra)
> JWT authentication (shared secret, stays the same)
> Redis caching (all cached endpoints remain cached)
> MongoDB event logging (Observer pattern stays in place)

The M3 grader still tests every M2 invariant below. None of these were retired.

---

## Strategy boundary (DP-1)

Strategy pattern is used **only** in S5-F12 (refund logic, payment-service). Do not introduce a `RefundStrategy`/`Strategy`-named class anywhere else — graded by grep.

- `payment-service` must NOT contain `if (refundSurge)` (or any equivalent boolean branching for surge handling). The selector picks one of three strategies and the service calls `selector.select(payment, request).calculateRefund(...)`.
- No M1 method should be retrofitted to use Strategy.

## Ownership-check pattern

Used by S1-F12 (`GET /api/users/{id}/activity`) and S3-F12 (`GET /api/rides/recommendations`):

- Compare the JWT `uid` claim (numeric — or in M3 the gateway-forwarded `X-User-Id`) directly against the path/query userId. **No PG lookup** on the hot path.
- Caller passes if `uid == target` OR `role == "ADMIN"`. Otherwise 403 (not 404 — exposing 404 leaks existence).

In M3 this generalizes to §2.10 (see `docs/m3/jwt-contract.md`) for every path-param endpoint.

## Dashboard logging-on-cache-hit

Dashboard features (S2-F12, S3-F10, S4-F10, S5-F10) emit their `*_VIEWED` event on **every** invocation, **including cache hits**. The logging step must run **outside** the cache decorator/layer so cache hits log too.

These observability writes (`ANALYTICS_VIEWED`, `DASHBOARD_VIEWED`) are **excluded from observer-driven cache invalidation** — match on the action string before invalidating, otherwise you get a self-defeating cycle.

## Distinct-endpoint rule for M2-vs-M1 collisions

Several M2 features added new paths that coexist with M1 endpoints. **Both must coexist** in M3 — do not overwrite the M1 endpoint when refactoring.

- S2-F10 `/api/drivers/search/full-text` ≠ M1 `/api/drivers/search`
- S3-F10 `/api/rides/analytics/dashboard` ≠ M1 `/api/rides/analytics`
- S5-F12 `/api/payments/{id}/refund-surge-adjusted` ≠ M1 `/api/payments/{id}/refund`

## Idempotency rule for S3-F11

`POST /api/rides/{rideId}/record-interaction` is idempotent on `rideId`. The idempotency marker lives **in Neo4j**, not PostgreSQL — M2 did not alter any M1 PG schema, and M3 does not change that.

Acceptable approaches:
- A `recorded_ride_ids` set on the `RODE_WITH` relationship.
- A sentinel `(:User)-[:RECORDED_RIDE {rideId}]->(:Driver)` node checked with `EXISTS`.

Idempotent re-calls return 200 immediately and do **not** emit `INTERACTION_RECORDED` (per `docs/m3/event-actions.md`).

## CRUD writes auto-index drivers to ES

The Driver entity must auto-sync to Elasticsearch on every CRUD POST/PUT (re-index) and DELETE (remove). Implement via JPA entity listener (`@PostPersist`/`@PostUpdate`/`@PostRemove`) or a service-level hook. **Do not** inline the ES call in every controller method.

The explicit `POST /api/drivers/{id}/index` endpoint (S2-F11) and the auto-index path emit `INDEXED` events with `details.source ∈ {"explicit", "auto_crud_create", "auto_crud_update"}` (and `DRIVER_DELETED` on remove).

## 15% surge fallback

Pre-M2 Payment rows lack `transactionDetails.surgeFee`. Any reader (S5-F10 in particular) treats a missing/null `surgeFee` as `0.15 * amount` (15% of total).

- New writes (M1 S5-F4 retrofit) compute the fee from `Ride.metadata.surgeMultiplier` if present (`baseFare * (multiplier - 1)`), else 15% of total.
- No DB backfill migration. Old rows stay null; the reader falls back.

## Observer chain rules (DP-2, DP-6)

- Mongo writes go through `MongoEventLogger` only. Never `@EventListener` to Mongo. Never `new <Event>(...)` — go through `EventFactory`.
- Each service binds its `MongoEventLogger` to a **fixed** `EventType` at construction (user→AUTH, driver→DRIVER, ride→RIDE, location→LOCATION, payment→PAYMENT_AUDIT). Observer registration is per-service, not shared.
- The action string (UPPER_SNAKE_CASE) passed to `notifyObservers(actionString, payload)` is **NOT** the EventType passed to the factory. The action string travels in `params["action"]`.
- Payment-shaped actions (CREATED/COMPLETED/FAILED/REFUNDED/REFUND_DENIED/RETRY_ATTEMPTED) **must** carry `method` and `amount` on the event. Otherwise S5-F11 silently drops the event from the breakdown.

## Six-database soft-vs-hard dependencies (M2 §6.3)

- **PostgreSQL** = hard. Service won't boot without it. (M3: each service has its own PG.)
- **Mongo / Redis / ES / Neo4j / Cassandra** = soft. Service must still boot when these are down. NoSQL failures `log.warn` and **must not** roll back the upstream PG transaction. `MongoEventLogger` in particular catches and swallows Mongo exceptions.
