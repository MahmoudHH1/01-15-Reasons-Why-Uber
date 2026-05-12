<!-- Loaded by: skills/observer-bootstrap, skills/rabbitmq-bootstrap, skills/pr-check, skills/m3-orchestrator -->

# M3 Event Actions & RabbitMQ Routing Keys

Two parallel event surfaces in M3:

1. **MongoDB Observer chain** — internal-state audit log per service. Action vocabulary carries over verbatim from M2 per uber-m3.md:44 ("MongoDB event logging (Observer pattern stays in place)"). UPPER_SNAKE_CASE.
2. **RabbitMQ TopicExchange** — inter-service async events for choreography saga + side effects. Routing keys are dotted lowercase (e.g., `ride.completed`).

The two are independent. A single business write may emit both: an Observer event to its local MongoDB collection AND a RabbitMQ event to its TopicExchange.

The action string passed to `notifyObservers(actionString, payload)` is **NOT** the EventType passed to `EventFactory.createEvent(EventType, params)`. Each `MongoEventLogger` is bound to a fixed EventType at construction; the action string travels in `params.put("action", actionString)`.

---

## auth_events (user-service) — Mongo

EventType binding: `EventType.AUTH`. Class: `AuthEvent`. Distinct field: `userId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `REGISTERED` | S1-F10 POST `/api/auth/register` | success only |
| `LOGGED_IN` | S1-F11 POST `/api/auth/login` | success only |
| `ROLE_CHANGED` | CC-2 PUT `/api/users/{id}/role` | `details` includes old + new role |
| `USER_UPDATED` | S1-F2 PUT `/api/users/{id}/preferences` | retrofit |
| `USER_DEACTIVATED` | S1-F4 | retrofit |
| `DEFAULT_ADDRESS_SET` | S1-F7 | retrofit |
| `USER_CREATED` | POST `/api/users` (CRUD) | retrofit |
| `USER_DELETED` | DELETE `/api/users/{id}` (CRUD) | retrofit |

---

## driver_events (driver-service) — Mongo

EventType binding: `EventType.DRIVER`. Class: `DriverEvent`. Distinct field: `driverId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `INDEXED` | S2-F11 POST `/api/drivers/{id}/index` | `details.source ∈ {"explicit", "auto_crud_create", "auto_crud_update"}` |
| `UPDATED` | various M1 update endpoints | retrofit |
| `DASHBOARD_VIEWED` | S2-F12 GET `/api/drivers/{id}/dashboard` | written on **every** invocation including cache hits; **excluded from invalidation** |
| `VEHICLE_DETAILS_UPDATED` | S2-F2 | retrofit |
| `AVAILABILITY_UPDATED` | S2-F4 | retrofit |
| `RATING_RECORDED` | S2-F7 | retrofit |
| `DOCUMENT_VERIFIED` | S2-F8 | retrofit |
| `DRIVER_CREATED` | POST `/api/drivers` (CRUD) | retrofit; auto-index to ES happens here |
| `DRIVER_DELETED` | DELETE `/api/drivers/{id}` (CRUD) | retrofit; auto-remove from ES |

---

## ride_events (ride-service) — Mongo

EventType binding: `EventType.RIDE`. Class: `RideEvent`. Distinct field: `rideId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `ANALYTICS_VIEWED` | S3-F10 GET `/api/rides/analytics/dashboard` | every invocation; **excluded from invalidation** |
| `INTERACTION_RECORDED` | S3-F11 POST `/api/rides/{rideId}/record-interaction` | only on **non-idempotent** path; idempotent re-call must NOT emit |
| `DRIVER_ASSIGNED` | S3-F2 | retrofit |
| `RIDE_COMPLETED` | S3-F4 | retrofit (M3: also publishes `ride.completed` to RabbitMQ — see saga table below) |
| `RIDE_CANCELLED` | S3-F7 | retrofit (M3: also publishes `ride.cancelled` to RabbitMQ) |
| `STOPS_ADDED` | S3-F8 | retrofit |
| `RIDE_CREATED` | POST `/api/rides` (CRUD) | retrofit |
| `RIDE_DELETED` | DELETE `/api/rides/{id}` (CRUD) | retrofit |

---

## location_events (location-service) — Mongo

EventType binding: `EventType.LOCATION`. Class: `LocationEvent`. Distinct field: `driverId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `TRACKING_RECORDED` | S4-F11 POST `/api/locations/{driverId}/tracking` | written alongside the Cassandra row; both writes succeed independently |
| `ANALYTICS_VIEWED` | S4-F10 GET `/api/locations/analytics` | every invocation; **excluded from invalidation** |
| `LOCATION_UPDATED` | S4-F2 | retrofit |
| `BATCH_LOCATIONS_UPDATED` | S4-F4 | retrofit |
| `OLD_LOCATIONS_PURGED` | S4-F7 | retrofit |
| `LOCATION_DELETED` | DELETE `/api/locations/{id}` (CRUD) | retrofit |

---

## payment_audit_trail (payment-service) — Mongo

EventType binding: `EventType.PAYMENT_AUDIT`. Class: `PaymentAuditEvent`. Distinct fields: `paymentId:Long`, `method:String` (conditional), `amount:Double` (conditional).

`method` and `amount` are **required** on payment-shaped actions: `CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, RETRY_ATTEMPTED`. Omitted (null-permitted) on observability actions (`ANALYTICS_VIEWED`) and lifecycle retrofits (`COUPON_APPLIED`, `PAYMENT_DELETED`). `method` values: `CREDIT_CARD`, `CASH`, `WALLET`.

| Action | Trigger | method/amount | Notes |
|---|---|---|---|
| `CREATED` | M1 S5-F4 — Payment row first inserted | required | retrofit |
| `COMPLETED` | M1 S5-F4 — status transitions to COMPLETED | required | retrofit (M3: also publishes `payment.completed`) |
| `FAILED` | M1 S5-F4 with `?simulateFailure=true` | required | retrofit (M3: also publishes `payment.failed`) |
| `REFUNDED` | M1 S5-F2 + M2 S5-F12 | required | M2 path includes strategy name + surge inclusion in `details` (M3: also publishes `payment.refunded`) |
| `REFUND_DENIED` | M2 S5-F12 when `NoRefundStrategy` selected | required | `details` includes denial reason + strategy name |
| `ANALYTICS_VIEWED` | S5-F10 GET `/api/payments/analytics/vehicle-type` | omitted | every invocation; **excluded from invalidation** |
| `COUPON_APPLIED` | M1 S5-F5 | omitted | retrofit |
| `RETRY_ATTEMPTED` | M1 S5-F7 | required | retrofit |
| `PAYMENT_DELETED` | DELETE `/api/payments/{id}` (CRUD) | omitted | retrofit |

---

## RabbitMQ Routing Keys (uber-m3.md §2.9)

Verbatim from uber-m3.md:319–333. Exchange type for all is **TopicExchange** (uber-m3.md:286).

| Producer | Exchange | Routing key | Payload record | Consumers |
|---|---|---|---|---|
| user-service | `user.events` | `user.registered` | `UserRegisteredEvent` | ride-service |
| user-service | `user.events` | `user.deactivated` | `UserDeactivatedEvent` | ride-service |
| driver-service | `driver.events` | `driver.status-changed` | `DriverStatusChangedEvent` | (observability only) |
| driver-service | `driver.events` | `driver.rated` | `DriverRatedEvent` | (observability only) |
| driver-service | `driver.events` | `driver.document.verified` | `DriverDocumentVerifiedEvent` | (audit only) |
| ride-service | `ride.events` | `ride.placed` | `RidePlacedEvent` | driver-service |
| ride-service | `ride.events` | `ride.completed` | `RideCompletedEvent` | user-service, driver-service, location-service, payment-service |
| ride-service | `ride.events` | `ride.cancelled` | `RideCancelledEvent` | user-service, driver-service, location-service, payment-service |
| location-service | `location.events` | `location.tracked` | `LocationTrackedEvent` | (audit only) |
| payment-service | `payment.events` | `payment.initiated` | `PaymentInitiatedEvent` | ride-service |
| payment-service | `payment.events` | `payment.completed` | `PaymentCompletedEvent` | ride-service |
| payment-service | `payment.events` | `payment.failed` | `PaymentFailedEvent` | ride-service |
| payment-service | `payment.events` | `payment.refunded` | `PaymentRefundedEvent` | ride-service |

DLQ topology: every consumer queue has `x-dead-letter-exchange` + `x-dead-letter-routing-key` arguments. With `acknowledge-mode: auto` + `default-requeue-rejected: false` + `max-attempts: 3`, listener exceptions retry then route to DLQ. (uber-m3.md:2637–2638)

---

## Hard Rules (graded)

1. **No `new <Event>(...)` outside `EventFactory`.** Source-scan: `grep -rEn "new (AuthEvent|DriverEvent|RideEvent|LocationEvent|PaymentAuditEvent)\b" --include='*.java' src/main/java/ | grep -v EventFactory.java` must be empty.
2. **No `@EventListener` writes to MongoDB.** Source-scan: `grep -rEln "@EventListener" --include='*.java' src/main/java/ | xargs -r grep -ln "MongoTemplate\|MongoRepository"` must be empty.
3. **Mongo failures `log.warn` and do not rethrow.** `MongoEventLogger` swallows the exception so the upstream PG transaction commits.
4. Each service binds its `MongoEventLogger` to a **fixed** EventType.
5. **Consumers must be idempotent.** Per uber-m3.md:2645 — state-based: read the target row's status before mutating. RabbitMQ delivery is at-least-once.
6. **Publish-after-commit** (no outbox per uber-m3.md:2.11). Local PG transaction commits first, then RabbitMQ publish.
