---
name: cache-audit
description: Audit Redis caching coverage and invalidation against the M2 spec — verifies that all 27 cached M1 GETs + 10 CRUD GET-by-ID + 15 M2 reads are cached with correct keys/TTLs, and that every write invalidates the right wildcard pattern. Run after M1 retrofits land and again before each PR.
---

# Cache Audit

You are verifying that the Redis caching layer matches `Uber_descriptionM2.pdf` §4.4 exactly. The auto-grader inspects Redis between calls (`redis-cli KEYS '...'`) and times two consecutive calls — the second must be faster. Missed cache keys, wrong TTLs, or missed invalidations all cost points.

## Sources of Truth (Read First)

1. **`docs/m2/cache-matrix.md`** — canonical cache key + TTL + invalidation enumeration. **Read this before starting.** The tables in this skill are a quick-reference summary; the doc is the source of truth.
2. **`Uber_descriptionM2.pdf` §4.4 + §8** — the spec text. Use the `pdf-clause-finder` agent if you need a verbatim clause.

If `docs/m2/cache-matrix.md` and this skill ever disagree, trust the doc and flag the skill drift to the user.

## Step 1: Setup

Confirm the stack is up:

```
docker compose ps   # postgres, redis, mongo, elasticsearch, neo4j, cassandra all healthy
```

If Redis is not running, start it (`docker compose up -d redis`). The audit needs to run live calls and inspect Redis.

Get a token for an authenticated user (the audit hits protected endpoints):

```
curl -s -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"<seeded-user>","password":"<password>"}' | jq -r .token
```

Store it as `$TOKEN`.

## Step 2: Cache-Read Audit (37 endpoints + 15 M2 reads)

For each endpoint below, run twice with the same params + token, then check Redis. Use the test loop at the end of this file.

### M1 cached features (27 total)

| Service | Cached features | Key prefix |
|---|---|---|
| user-service | F1, F3, F5, F6, F8, F9 | `user-service::S1-F<m>::*` |
| driver-service | F1, F3, F5, F6, F9 (F8 is a write) | `driver-service::S2-F<m>::*` |
| ride-service | F1, F3, F5, F6, F9 (F8 is a write; F3 is POST cached by body hash, 5 min) | `ride-service::S3-F<m>::*` |
| location-service | F1, F3, F5, F6, F9 | `location-service::S4-F<m>::*` |
| payment-service | F1, F3, F6, F8, F9 (F5 is a write) | `payment-service::S5-F<m>::*` |

**Excluded (writes, must NOT be cached, must be in invalidation list)**: S2-F8, S3-F8, S5-F5.

### M1 CRUD GET-by-ID (10 entities)

`user, saved-address, driver, driver-document, ride, ride-stop, location, payment, coupon, payment-coupon`. Key format: `<service>::<entity>::<id>`. **List endpoints (`GET /api/<entity>`) must NOT be cached** — verify no key appears.

### M2 read features (cached endpoints)

| Feature | Key prefix | TTL |
|---|---|---|
| S1-F12 GET activity | `user-service::S1-F12::*` | 5 min |
| S2-F10 GET full-text search | `driver-service::S2-F10::*` | 5 min |
| S2-F11 (write — must invalidate `S2-F10::*`) | n/a | n/a |
| S2-F12 GET dashboard | `driver-service::S2-F12::*` | 10 min |
| S3-F10 GET analytics dashboard | `ride-service::S3-F10::*` | 10 min |
| S3-F11 (write — must invalidate `S3-F12::*`) | n/a | n/a |
| S3-F12 GET recommendations | `ride-service::S3-F12::*` | 5 min |
| S4-F10 GET location analytics | `location-service::S4-F10::*` | 10 min |
| S4-F11 (write — must invalidate `S4-F10::*` and `S4-F12::{driverId}`) | n/a | n/a |
| S4-F12 GET tracking timeline | `location-service::S4-F12::*` | 5 min |
| S5-F10 GET vehicle-type revenue | `payment-service::S5-F10::*` | 10 min |
| S5-F11 GET methods breakdown | `payment-service::S5-F11::*` | 10 min |
| S5-F12 (write — must invalidate `S5-F10::*`, `S5-F11::*`, `payment::{id}`) | n/a | n/a |

### TTL Guidelines (§8.1)

| Data Type | TTL |
|---|---|
| Dashboards / analytics | 10 min |
| Search results | 5 min |
| Activity feeds | 5 min |
| Entity detail | 15 min |

Per-feature TTLs from §4.4.1: F1=5m, F3=10m (DTO), F5=5m (JSONB), F6=10m (report), F8=15m (relationship), F9=10m (combined).

## Step 3: Per-Endpoint Audit Loop

For every cached endpoint:

```
# 1. Clear that endpoint's cache pattern
redis-cli -a redispass --scan --pattern '<service>::S<n>-F<m>::*' | xargs -r redis-cli -a redispass DEL

# 2. First call — measures miss
T1=$(date +%s.%N)
curl -s -H "Authorization: Bearer $TOKEN" '<full-url>' > /dev/null
T2=$(date +%s.%N)
MISS_LATENCY=$(echo "$T2 - $T1" | bc)

# 3. Inspect Redis — key must exist
redis-cli -a redispass --scan --pattern '<service>::S<n>-F<m>::*'

# 4. Check TTL
KEY=$(redis-cli -a redispass --scan --pattern '<service>::S<n>-F<m>::*' | head -1)
redis-cli -a redispass TTL "$KEY"   # must be ≤ expected TTL in seconds, > 0

# 5. Second call — measures hit
T3=$(date +%s.%N)
curl -s -H "Authorization: Bearer $TOKEN" '<full-url>' > /dev/null
T4=$(date +%s.%N)
HIT_LATENCY=$(echo "$T4 - $T3" | bc)

# 6. Assert HIT_LATENCY < MISS_LATENCY
```

For CRUD GET-by-ID: same pattern, but key is `<service>::<entity>::<id>`.

For list endpoints (`GET /api/<entity>`): call twice, then `redis-cli ... KEYS '<service>::<entity>::*'` should NOT produce a list-response key. List endpoints must hit PG every time.

## Step 4: Invalidation Audit (48 M1 paths + M2 writers)

For each write, trigger it then verify the affected keys are gone.

### M1 feature writes (18) — entity-write rule

| Service | Writes | Invalidates |
|---|---|---|
| S1 | F2, F4, F7 | `user-service::user::{id}` + matching `user-service::S1-F<m>::*` |
| S2 | F2, F4, F7, **F8 (Verify Driver Doc)** | `driver-service::driver::{id}` + matching feature caches |
| S3 | F2, F4, F7, **F8 (Add Stops to Existing Ride)** | `ride-service::ride::{id}` + matching feature caches |
| S4 | F2, F4, F7 | `location-service::location::{id}` + matching feature caches |
| S5 | F2, F4, **F5 (Apply Coupon)**, F7 | `payment-service::payment::{id}` + matching feature caches |

### M1 CRUD writes (30 — POST/PUT/DELETE on each of 10 entities)

- POST: nothing cached yet for the new entity, no invalidation needed.
- PUT/DELETE: invalidate `<service>::<entity>::{id}` + every `<service>::S<n>-F<m>::*` whose output may include that entity.

### M2 invalidation rules (in addition to M1)

- **CC-2 role mgmt** (PUT /api/users/{id}/role): invalidate `user-service::user::{id}` and `user-service::S1-F12::*`.
- Any M1 write that creates/updates a Ride referencing a `driverId` → invalidate `driver-service::S2-F12::{driverId}`.
- Any M1 write that creates/updates a Ride → invalidate `ride-service::S3-F10::*`.
- Any M1 write that creates/updates a Location → invalidate `location-service::S4-F10::*`.
- Any M1 write that creates/updates a Payment (S5-F4 / S5-F2 / S5-F12) → invalidate `payment-service::S5-F10::*` and `payment-service::S5-F11::*`.

### NoSQL-writer → cached-reader invalidation (required, easy to miss)

- **S4-F11 POST tracking** writes Cassandra and emits TRACKING_RECORDED → must invalidate `location-service::S4-F12::{driverId}` and `location-service::S4-F10::*`.
- **S3-F11 POST record-interaction** writes Neo4j → must invalidate `ride-service::S3-F12::*` (wildcard — a new edge can change recommendations for ANY user sharing a driver).
- **S2-F11 POST /index AND every Driver CRUD write that triggers auto-index** → must invalidate `driver-service::S2-F10::*`.
- **S5-F10 / S5-F11 analytics writes** — whenever any Observer writes a data-mutating event to `payment_audit_trail` (CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, COUPON_APPLIED) → invalidate `payment-service::S5-F10::*` and `payment-service::S5-F11::*`. Same pattern for `driver_events` / `ride_events` / `location_events` → `S2-F12 / S3-F10 / S4-F10` keys.
- **ANALYTICS_VIEWED and DASHBOARD_VIEWED do NOT invalidate** — explicitly excluded. Match on the `action` field before invalidating.

### Audit loop per write

```
# 1. Prime the cache by calling the affected read
curl -s -H "Authorization: Bearer $TOKEN" '<read-url>' > /dev/null
redis-cli -a redispass --scan --pattern '<expected-key-pattern>'   # confirm key exists

# 2. Trigger the write
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '<body>' '<write-url>'

# 3. Verify the cache key is gone
redis-cli -a redispass --scan --pattern '<expected-key-pattern>'   # must be empty
```

## Step 5: Soft-dependency Test

Per §6.3, NoSQL stores are soft deps. Stop Redis (`docker compose stop redis`) and call any cached endpoint — it must still return correct data from PG (graceful degradation). Restart Redis (`docker compose start redis`) and confirm caching resumes on the next call.

## Step 6: Output Report

```
Cache Audit
═══════════
Cached reads (M1 features):    [X/27 PASS]
Cached reads (CRUD GET-by-ID): [X/10 PASS]
Cached reads (M2 features):    [X/Y PASS]
List endpoints not cached:     [PASS/FAIL]
TTLs match spec:               [PASS/FAIL]
Latency (hit < miss):          [PASS/FAIL]

Invalidation (M1 feature writes): [X/18 PASS]
Invalidation (M1 CRUD writes):    [X/30 PASS]
Invalidation (M2 NoSQL writers):  [X/Y PASS]
Observer-driven invalidation:     [PASS/FAIL]
ANALYTICS_VIEWED excluded:        [PASS/FAIL]

Soft-dep graceful degradation:    [PASS/FAIL]

Overall: READY / NOT READY
```

For each FAIL, list the exact endpoint + observed key + expected key + latency / TTL numbers.
