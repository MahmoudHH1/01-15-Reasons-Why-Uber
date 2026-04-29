# M2 Cache Matrix

Source of truth: `Uber_descriptionM2.pdf` §4.4 (cached reads, invalidation rules), §8 (TTL guidelines). Keep this file in sync with code; the auto-grader inspects Redis directly.

**Cache key conventions (§4.4.5):**
- Entity detail: `<service>::<entity>::<id>` — e.g., `user-service::user::42`
- Feature result: `<service>::S<n>-F<m>::<param-hash>` — e.g., `user-service::S1-F3::42`

**Wildcard invalidation (§4.4.6):** `redis-cli SCAN + DEL` (or `KEYS + UNLINK` for small caches). Over-invalidation is acceptable.

---

## TTL Guidelines

| Data Type | TTL |
|---|---|
| Dashboards / analytics | 10 min |
| Search results | 5 min |
| Activity feeds | 5 min |
| Entity detail views | 15 min |

**Per-feature TTLs (§4.4.1):** F1=5m (search), F3=10m (DTO), F5=5m (JSONB), F6=10m (report), F8=15m (relationship), F9=10m (combined).

---

## M1 Cached Feature GETs (27 endpoints)

| Service | Feature IDs cached | Notes |
|---|---|---|
| S1 user-service | F1, F3, F5, F6, F8, F9 | |
| S2 driver-service | F1, F3, F5, F6, F9 | F8 is a write (Verify Driver Doc) — excluded |
| S3 ride-service | F1, F3, F5, F6, F9 | F8 is a write (Add Stops to Ride); F3 is POST cached by body hash, 5 min |
| S4 location-service | F1, F3, F5, F6, F9 | |
| S5 payment-service | F1, F3, F6, F8, F9 | F5 is a write (Apply Coupon) — excluded |

**Excluded from caching, included in invalidation list:** S2-F8, S3-F8, S5-F5.

---

## M1 CRUD GET-by-ID Cached (10 entities)

`user, saved-address, driver, driver-document, ride, ride-stop, location, payment, coupon, payment-coupon`.

- `GET /api/<entity>/{id}` cached — TTL 15 min — key `<service>::<entity>::<id>`.
- `GET /api/<entity>` (list) **NOT** cached — must hit PG every time.

---

## M2 Read Features Cached (12 read endpoints)

| Feature | Path | Key prefix | TTL |
|---|---|---|---|
| S1-F12 | `GET /api/users/{id}/activity` | `user-service::S1-F12::*` | 5 min |
| S2-F10 | `GET /api/drivers/search/full-text` | `driver-service::S2-F10::*` | 5 min |
| S2-F12 | `GET /api/drivers/{id}/dashboard` | `driver-service::S2-F12::*` | 10 min |
| S3-F10 | `GET /api/rides/analytics/dashboard` | `ride-service::S3-F10::*` | 10 min |
| S3-F12 | `GET /api/rides/recommendations` | `ride-service::S3-F12::*` | 5 min |
| S4-F10 | `GET /api/locations/analytics` | `location-service::S4-F10::*` | 10 min |
| S4-F12 | `GET /api/locations/{driverId}/tracking` | `location-service::S4-F12::*` | 5 min |
| S5-F10 | `GET /api/payments/analytics/vehicle-type` | `payment-service::S5-F10::*` | 10 min |
| S5-F11 | `GET /api/payments/analytics/methods` | `payment-service::S5-F11::*` | 10 min |

Pure observability actions written by the Observer chain on every cache hit (`ANALYTICS_VIEWED`, `DASHBOARD_VIEWED`) **must NOT** invalidate caches — that's a self-defeating cycle. Match on the action string before invalidating.

---

## Invalidation — M1 Feature Writes (18)

| Service | Writes | Invalidate |
|---|---|---|
| S1 | F2, F4, F7 | `user-service::user::{id}` + matching `user-service::S1-F<m>::*` |
| S2 | F2, F4, F7, **F8** (Verify Driver Doc) | `driver-service::driver::{id}` + matching feature caches |
| S3 | F2, F4, F7, **F8** (Add Stops to Ride) | `ride-service::ride::{id}` + matching feature caches |
| S4 | F2, F4, F7 | `location-service::location::{id}` + matching feature caches |
| S5 | F2, F4, **F5** (Apply Coupon), F7 | `payment-service::payment::{id}` + matching feature caches |

---

## Invalidation — M1 CRUD Writes (30)

For each of the 10 entities × {POST, PUT, DELETE}:

- **POST** — nothing cached yet for the new entity, no invalidation.
- **PUT** — invalidate `<service>::<entity>::{id}` + every `<service>::S<n>-F<m>::*` whose output may include that entity.
- **DELETE** — same as PUT.

**Total M1 invalidating paths: 18 feature writes + 30 CRUD writes = 48.**

---

## Invalidation — M2-Specific Rules

These are in addition to M1 rules. Several are NoSQL-writer paths the M1 rules don't cover.

| Trigger | Invalidate |
|---|---|
| **CC-2** PUT `/api/users/{id}/role` | `user-service::user::{id}` + `user-service::S1-F12::*` |
| Any M1 write that creates/updates a Ride referencing `driverId` | `driver-service::S2-F12::{driverId}` |
| Any M1 write that creates/updates a Ride | `ride-service::S3-F10::*` |
| Any M1 write that creates/updates a Location | `location-service::S4-F10::*` |
| Any M1 write that creates/updates a Payment (S5-F4, S5-F2, S5-F12) | `payment-service::S5-F10::*` + `payment-service::S5-F11::*` |
| **S4-F11** POST `/api/locations/{driverId}/tracking` | `location-service::S4-F12::{driverId}` + `location-service::S4-F10::*` |
| **S3-F11** POST `/api/rides/{rideId}/record-interaction` | `ride-service::S3-F12::*` (wildcard — recommendations cross-affect users) |
| **S2-F11** POST `/api/drivers/{id}/index` AND every Driver CRUD write triggering auto-index | `driver-service::S2-F10::*` |
| **S5-F10/F11 analytics writes** — any data-mutating Observer event to `payment_audit_trail` (CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, COUPON_APPLIED) | `payment-service::S5-F10::*` + `payment-service::S5-F11::*` |
| Any Observer write of a data-mutating action to `driver_events` / `ride_events` / `location_events` | corresponding `S2-F12 / S3-F10 / S4-F10` analytics keys |

**Excluded from invalidation:** `ANALYTICS_VIEWED`, `DASHBOARD_VIEWED`. Match on the `action` field before invalidating.

---

## Auditing

Run the `cache-audit` skill before each PR. It loops through every endpoint, hits Redis between calls, and verifies key/TTL/latency/invalidation per the rules above.
