# M2 Event Actions

Source of truth: `Uber_descriptionM2.pdf` §7.1. All action values are **UPPER_SNAKE_CASE**. Lists are non-exhaustive — extend with domain-appropriate values when M1 retrofit observers fire on additional endpoints.

The action string passed to `notifyObservers(actionString, payload)` is **NOT** the EventType passed to `EventFactory.createEvent(EventType, params)`. Each `MongoEventLogger` is bound to a fixed EventType at construction; the action string travels in `params.put("action", actionString)` and ends up on `MongoEvent.getAction()`.

---

## auth_events (user-service)

EventType binding: `EventType.AUTH`. Class: `AuthEvent` (implements `MongoEvent`). Distinct field: `userId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `REGISTERED` | S1-F10 POST `/api/auth/register` | success only |
| `LOGGED_IN` | S1-F11 POST `/api/auth/login` | success only |
| `ROLE_CHANGED` | CC-2 PUT `/api/users/{id}/role` | `details` includes old + new role |
| `USER_UPDATED` | S1-F2 PUT `/api/users/{id}/preferences` (M1) | retrofit |
| `USER_DEACTIVATED` | S1-F4 (M1) | retrofit |
| `DEFAULT_ADDRESS_SET` | S1-F7 (M1) | retrofit |
| `USER_CREATED` | POST `/api/users` (CRUD) | retrofit |
| `USER_DELETED` | DELETE `/api/users/{id}` (CRUD) | retrofit |

---

## driver_events (driver-service)

EventType binding: `EventType.DRIVER`. Class: `DriverEvent`. Distinct field: `driverId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `INDEXED` | S2-F11 POST `/api/drivers/{id}/index` | `details.source ∈ {"explicit", "auto_crud_create", "auto_crud_update"}` |
| `UPDATED` | various M1 update endpoints | retrofit |
| `DASHBOARD_VIEWED` | S2-F12 GET `/api/drivers/{id}/dashboard` | written on **every** invocation including cache hits; **excluded from invalidation** |
| `VEHICLE_DETAILS_UPDATED` | S2-F2 (M1) | retrofit |
| `AVAILABILITY_UPDATED` | S2-F4 (M1) | retrofit |
| `RATING_RECORDED` | S2-F7 (M1) | retrofit |
| `DOCUMENT_VERIFIED` | S2-F8 (M1) | retrofit |
| `DRIVER_CREATED` | POST `/api/drivers` (CRUD) | retrofit; auto-index to ES happens here |
| `DRIVER_DELETED` | DELETE `/api/drivers/{id}` (CRUD) | retrofit; auto-remove from ES |

---

## ride_events (ride-service)

EventType binding: `EventType.RIDE`. Class: `RideEvent`. Distinct field: `rideId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `ANALYTICS_VIEWED` | S3-F10 GET `/api/rides/analytics/dashboard` | every invocation; **excluded from invalidation** |
| `INTERACTION_RECORDED` | S3-F11 POST `/api/rides/{rideId}/record-interaction` | only on **non-idempotent** path; idempotent re-call must NOT emit |
| `DRIVER_ASSIGNED` | S3-F2 (M1) | retrofit |
| `RIDE_COMPLETED` | S3-F4 (M1) | retrofit |
| `RIDE_CANCELLED` | S3-F7 (M1) | retrofit |
| `STOPS_ADDED` | S3-F8 (M1) | retrofit |
| `RIDE_CREATED` | POST `/api/rides` (CRUD) | retrofit |
| `RIDE_DELETED` | DELETE `/api/rides/{id}` (CRUD) | retrofit |

---

## location_events (location-service)

EventType binding: `EventType.LOCATION`. Class: `LocationEvent`. Distinct field: `driverId:Long`.

| Action | Trigger | Notes |
|---|---|---|
| `TRACKING_RECORDED` | S4-F11 POST `/api/locations/{driverId}/tracking` | written alongside the Cassandra row; both writes succeed independently |
| `ANALYTICS_VIEWED` | S4-F10 GET `/api/locations/analytics` | every invocation; **excluded from invalidation** |
| `LOCATION_UPDATED` | S4-F2 (M1) | retrofit |
| `BATCH_LOCATIONS_UPDATED` | S4-F4 (M1) | retrofit |
| `OLD_LOCATIONS_PURGED` | S4-F7 (M1) | retrofit |
| `LOCATION_DELETED` | DELETE `/api/locations/{id}` (CRUD) | retrofit |

---

## payment_audit_trail (payment-service)

EventType binding: `EventType.PAYMENT_AUDIT`. Class: `PaymentAuditEvent`. Distinct fields: `paymentId:Long`, `method:String` (conditional), `amount:Double` (conditional).

`method` and `amount` are **required** on payment-shaped actions: `CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, RETRY_ATTEMPTED`. Omitted (null-permitted) on observability actions (`ANALYTICS_VIEWED`) and lifecycle retrofits (`COUPON_APPLIED`, `PAYMENT_DELETED`). `method` values: `CREDIT_CARD`, `CASH`, `WALLET` (matches M1 Payment.method enum).

| Action | Trigger | method/amount | Notes |
|---|---|---|---|
| `CREATED` | M1 S5-F4 (Process Payment) — Payment row first inserted | required | retrofit |
| `COMPLETED` | M1 S5-F4 — status transitions to COMPLETED | required | retrofit |
| `FAILED` | M1 S5-F4 with `?simulateFailure=true` | required | retrofit; short-circuits to `Payment.status=FAILED` |
| `REFUNDED` | M1 S5-F2 (simple refund) **and** M2 S5-F12 (refund-surge-adjusted) | required | M2 path includes strategy name + surge inclusion in `details` |
| `REFUND_DENIED` | M2 S5-F12 when `NoRefundStrategy` selected | required | `details` includes denial reason + strategy name |
| `ANALYTICS_VIEWED` | S5-F10 GET `/api/payments/analytics/vehicle-type` | omitted | every invocation; **excluded from invalidation** |
| `COUPON_APPLIED` | M1 S5-F5 | omitted | retrofit |
| `RETRY_ATTEMPTED` | M1 S5-F7 | required | retrofit |
| `PAYMENT_DELETED` | DELETE `/api/payments/{id}` (CRUD) | omitted | retrofit |

S5-F11 (Payment Method Breakdown) groups events by `method` and only counts those with `method` populated — a `CREATED/COMPLETED/FAILED` event written without `method` would silently vanish from the breakdown. The factory must always populate `method` for these actions.

---

## Hard Rules (graded)

1. **No `new <Event>(...)` outside `EventFactory`.** Source-scan: `grep -rEn "new (AuthEvent|DriverEvent|RideEvent|LocationEvent|PaymentAuditEvent)\b" --include='*.java' src/main/java/ | grep -v EventFactory.java` must be empty.
2. **No `@EventListener` writes to MongoDB.** Source-scan: `grep -rEln "@EventListener" --include='*.java' src/main/java/ | xargs -r grep -ln "MongoTemplate\|MongoRepository"` must be empty.
3. **Mongo failures `log.warn` and do not rethrow.** `MongoEventLogger` swallows the exception so the upstream PG transaction commits.
4. Each service binds its `MongoEventLogger` to a **fixed** EventType. Observer registration is per-service, not shared.
5. The action string is NOT the EventType. The factory dispatches on the bound EventType; the action string lives in `params["action"]`.
