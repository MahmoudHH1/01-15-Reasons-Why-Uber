#!/usr/bin/env bash
# driver-service (port 8082) — full endpoint coverage.
# Spec sections covered:
#   §10.2.1 S2-F10 Full-Text Driver Search
#   §10.2.2 S2-F11 Index Driver for Search   (+ auto-index on CRUD)
#   §10.2.3 S2-F12 Driver Performance Dashboard
#   §4.5    DP-2 Observer retrofits to M1 (driver_events for VEHICLE_DETAILS_UPDATED, AVAILABILITY_UPDATED, etc.)
#   §7.2    DriverSearchDocument (Elasticsearch)
#   §4.4    Cache contract on S2-F1, S2-F3, S2-F5, S2-F6, S2-F9, driver-by-id, driver-document-by-id
#   M1 S2-F1..S2-F9 + CRUD Driver + CRUD DriverDocument

source "$(dirname "$0")/lib/common.sh"

section "20 driver-service — S2-F10/F11/F12 + M1 + CRUD"

BASE="$DRIVER_URL"
TOKEN="$(register_user "drv")"
if [ -z "$TOKEN" ]; then
  fail "register seed user via user-service" \
       "user-service not reachable or registration broken — entire script skipped"
  exit 0
fi

# Helper to create a driver
create_driver() {
  local name="$1" desc="$2" type="${3:-SEDAN}" status="${4:-AVAILABLE}" rating="${5:-4.0}"
  local salt="${RANDOM}${RANDOM}"
  http_auth POST "$BASE/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"$name","email":"${name// /-}-${salt}@x.io","phone":"+1${salt:0:10}",
 "licenseNumber":"LIC-${salt}",
 "rating":$rating,"totalRatings":10,"status":"$status","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"$type","plate":"PL-${salt}","description":"$desc"}}
EOF
)"
  echo "$LAST_BODY" | jq -r '.id // empty'
}

# ============================================================
# CRUD Driver  + auto-index retrofit (§4.5 "Driver CRUD auto-index")
# ============================================================

D_TOYOTA="$(create_driver "Toyota Champ" "luxury toyota camry" SEDAN AVAILABLE 4.7)"
D_VAN="$(create_driver "Hyundai Van" "spacious hyundai van" VAN AVAILABLE 4.2)"
D_SUV="$(create_driver "BMW Beast" "premium bmw x5" SUV BUSY 4.9)"
[ -n "$D_TOYOTA" ] && pass "CRUD POST /api/drivers (Toyota)" || fail "CRUD POST /api/drivers (Toyota)"
[ -n "$D_VAN" ]    && pass "CRUD POST /api/drivers (Van)"    || fail "CRUD POST /api/drivers (Van)"
[ -n "$D_SUV" ]    && pass "CRUD POST /api/drivers (SUV)"    || fail "CRUD POST /api/drivers (SUV)"

# (§4.5 step g) CRUD POST emits driver_events
de_count="$(mongo_count_poll driver_events "{ driverId: $D_TOYOTA }" 10)"
[ "${de_count:-0}" -ge 1 ] && pass "CRUD POST emits driver_events for $D_TOYOTA (§4.5.g)" \
                           || fail "CRUD POST emits driver_events for $D_TOYOTA (§4.5.g)"

# CRUD GET-by-id (cached, §4.4.2)
http_auth GET "$BASE/api/drivers/$D_TOYOTA" "$TOKEN"
assert_status 200 "CRUD GET /api/drivers/$D_TOYOTA"
http_auth GET "$BASE/api/drivers/$D_TOYOTA" "$TOKEN" >/dev/null
[ "$(redis_count_keys "driver-service::driver::$D_TOYOTA")" -ge 1 ] \
  && pass "GET-by-id caches driver-service::driver::$D_TOYOTA" \
  || fail "GET-by-id caches driver-service::driver::$D_TOYOTA"

# §4.4.2 — list endpoints must not create cache entries. Snapshot before
# and after; count must not grow.
before="$(redis_count_keys 'driver-service::driver::*')"
http_auth GET "$BASE/api/drivers" "$TOKEN" >/dev/null
after="$(redis_count_keys 'driver-service::driver::*')"
if [ "${after:-0}" -le "${before:-0}" ]; then
  pass "GET /api/drivers (list) NOT cached (§4.4.2; $before → $after)"
else
  fail "GET /api/drivers (list) NOT cached (§4.4.2)" \
       "key count grew $before → $after — list call created a cache entry"
fi

# CRUD PUT — invalidates entity detail
http_auth PUT "$BASE/api/drivers/$D_TOYOTA" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Toyota Champ Renamed","email":"toyota-champ-$RUN_ID@x.io","phone":"+1${RUN_ID:0:8}9",
 "licenseNumber":"LIC-TY-${RUN_ID}",
 "rating":4.7,"totalRatings":10,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"TY-$RUN_ID","description":"luxury toyota camry"}}
EOF
)"
assert_status 200 "CRUD PUT /api/drivers/$D_TOYOTA"
[ "$(redis_count_keys "driver-service::driver::$D_TOYOTA")" = "0" ] \
  && pass "PUT invalidates driver-service::driver::$D_TOYOTA" \
  || fail "PUT invalidates driver-service::driver::$D_TOYOTA"

# ============================================================
# S2-F11 Index Driver for Search   (§10.2.2)
# ============================================================
# Behaviour matrix:
#   a) Indexes a present driver → 200 + ES doc + INDEXED event
#   b) Driver missing description → still 200 (default empty)
#   c) Non-existent driver id → 404
#   d) Auto-index on PUT (no /index call) — later searched via S2-F10
#   e) Without token → 401
#   f) DELETE removes from ES + emits DRIVER_DELETED

http_auth POST "$BASE/api/drivers/$D_TOYOTA/index" "$TOKEN"
assert_status_in "S2-F11 POST /api/drivers/{id}/index → 200" 200 201

# c) 404 on non-existent
http_auth POST "$BASE/api/drivers/9999999/index" "$TOKEN"
assert_status 404 "S2-F11 unknown driver → 404"

# e) No token
http POST "$BASE/api/drivers/$D_TOYOTA/index"
assert_status 401 "S2-F11 no token → 401"

# Verify the ES document exists  (§7.2 fields: id, name, vehicleType, description, rating, status)
sleep 2
es_hit="$(curl -sS "$ES_URL/drivers/_doc/$D_TOYOTA" 2>/dev/null | jq -r '.found // false')"
if [ "$es_hit" = "true" ]; then
  pass "S2-F11 ES doc exists in 'drivers' index for $D_TOYOTA"
else
  fail "S2-F11 ES doc exists in 'drivers' index for $D_TOYOTA"
fi

# Verify INDEXED event in driver_events with source ∈ {explicit, auto_crud_create, auto_crud_update}
# (§10.2.2 step e — INDEXED event written to driver_events via Observer chain)
ix_count="$(mongo_count_poll driver_events "{ driverId: $D_TOYOTA, action: 'INDEXED' }" 10)"
[ "${ix_count:-0}" -ge 1 ] && pass "S2-F11 INDEXED event emitted (§10.2.2.e)" \
                           || fail "S2-F11 INDEXED event emitted (§10.2.2.e)"

# ============================================================
# S2-F10 Full-Text Driver Search   (§10.2.1)
# Distinct from M1 /api/drivers/search (§10.2.1 note).
# ============================================================

# Re-index every driver to make sure ES is in sync
for d in "$D_TOYOTA" "$D_VAN" "$D_SUV"; do
  http_auth POST "$BASE/api/drivers/$d/index" "$TOKEN" >/dev/null 2>&1
done
sleep 2

# (a) query=toyota → matches D_TOYOTA
http_auth GET "$BASE/api/drivers/search/full-text?query=toyota" "$TOKEN"
assert_status 200 "S2-F10 query=toyota"
hit="$(echo "$LAST_BODY" | jq -r '.[].id // empty' 2>/dev/null | grep -c "^${D_TOYOTA}$")"
[ "${hit:-0}" -ge 1 ] && pass "S2-F10 returns D_TOYOTA for query=toyota (§10.2.1.a)" \
                     || fail "S2-F10 returns D_TOYOTA for query=toyota (§10.2.1.a)" "got hits=$hit"

# (b) vehicleType filter
http_auth GET "$BASE/api/drivers/search/full-text?query=toyota&vehicleType=SEDAN" "$TOKEN"
assert_status_in "S2-F10 query=toyota&vehicleType=SEDAN" 200 204

# (c) status filter
http_auth GET "$BASE/api/drivers/search/full-text?query=toyota&status=AVAILABLE" "$TOKEN"
assert_status_in "S2-F10 status=AVAILABLE" 200 204

# (d) minRating filter
http_auth GET "$BASE/api/drivers/search/full-text?query=toyota&minRating=4.5&maxRating=5.0" "$TOKEN"
assert_status_in "S2-F10 minRating=4.5" 200 204

# (e) empty result — §10.2.1.f "Return an empty list if no matches" → 200
http_auth GET "$BASE/api/drivers/search/full-text?query=zzzzzzzz" "$TOKEN"
assert_status 200 "S2-F10 nonsense query → 200 (§10.2.1.f)"
empty="$(echo "$LAST_BODY" | jq -r 'length // 0' 2>/dev/null)"
[ "${empty:-99}" = "0" ] && pass "S2-F10 empty array on no matches (§10.2.1.f)" \
                         || fail "S2-F10 empty array on no matches (§10.2.1.f)" "got length=$empty"

# (f) cache for 5 min
http_auth GET "$BASE/api/drivers/search/full-text?query=toyota" "$TOKEN" >/dev/null
[ "$(redis_count_keys 'driver-service::S2-F10::*')" -ge 1 ] \
  && pass "S2-F10 caches driver-service::S2-F10::*" \
  || fail "S2-F10 caches driver-service::S2-F10::* (§4.4.1, §8.1)"

# (g) no token
http GET "$BASE/api/drivers/search/full-text?query=toyota"
assert_status 401 "S2-F10 no token → 401"

# (h) Auto-index on UPDATE: rename a driver, then search by new name
http_auth PUT "$BASE/api/drivers/$D_VAN" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Hyundai Van Renamed","email":"hyundai-van-$RUN_ID@x.io","phone":"+13${RUN_ID:0:8}",
 "licenseNumber":"LIC-VN-${RUN_ID}",
 "rating":4.2,"totalRatings":10,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"VAN","plate":"VN-$RUN_ID","description":"renamed van"}}
EOF
)" >/dev/null
sleep 2
http_auth GET "$BASE/api/drivers/search/full-text?query=Renamed" "$TOKEN"
hit="$(echo "$LAST_BODY" | jq -r '.[].id // empty' 2>/dev/null | grep -c "^${D_VAN}$")"
[ "${hit:-0}" -ge 1 ] && pass "S2-F10 reflects auto-indexed PUT (§4.5 auto-index)" \
                     || fail "S2-F10 reflects auto-indexed PUT (§4.5 auto-index)" "post-PUT search did not return D_VAN"

# (i) Auto-deindex on DELETE
http_auth DELETE "$BASE/api/drivers/$D_VAN" "$TOKEN" >/dev/null
sleep 2
http_auth GET "$BASE/api/drivers/search/full-text?query=Renamed" "$TOKEN"
gone="$(echo "$LAST_BODY" | jq -r '.[].id // empty' 2>/dev/null | grep -c "^${D_VAN}$")"
[ "${gone:-99}" = "0" ] && pass "S2-F10 deleted driver disappears from index (§4.5 auto-deindex)" \
                       || fail "S2-F10 deleted driver disappears from index (§4.5 auto-deindex)" "search still returns D_VAN"

# DELETE emits DRIVER_DELETED — §7.1.3 / §4.5 retrofit
del_count="$(mongo_count_poll driver_events "{ driverId: $D_VAN, action: 'DRIVER_DELETED' }" 10)"
[ "${del_count:-0}" -ge 1 ] && pass "DELETE emits DRIVER_DELETED (§7.1.3)" \
                            || fail "DELETE emits DRIVER_DELETED (§7.1.3)"

# ============================================================
# S2-F12 Driver Performance Dashboard   (§10.2.3)
# ============================================================

http_auth GET "$BASE/api/drivers/$D_TOYOTA/dashboard" "$TOKEN"
assert_status 200 "S2-F12 GET /api/drivers/{id}/dashboard"

# Validate DTO shape
for f in driverId name totalRides totalEarnings averageRideFare averageRating totalRatings; do
  v="$(echo "$LAST_BODY" | jq -r ".${f}")"
  [ "$v" != "null" ] && [ -n "$v" ] && pass "S2-F12 has $f=$v" || fail "S2-F12 has $f"
done

# §10.2.3 step a — exact dashboard math.
# Seed a driver explicitly with rating=4.5, totalRatings=100, then create
# 5 rides + 5 completed payments with amounts 100/200/150/300/250 (sum=1000)
# via ride-service + payment-service. Then dashboard must report:
#   totalRides=5, totalEarnings=1000, averageRideFare=200,
#   averageRating=4.5, totalRatings=100.
SALT_E="${RANDOM}${RANDOM}"
http_auth POST "$BASE/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Exact Math $SALT_E","email":"em-${SALT_E}@x.io","phone":"+10${SALT_E:0:9}",
 "licenseNumber":"LIC-EM-${SALT_E}","rating":4.5,"totalRatings":100,
 "status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"EM-${SALT_E}","description":"exact-math test"}}
EOF
)"
D_EXACT="$(echo "$LAST_BODY" | jq -r '.id // empty')"

if [ -n "$D_EXACT" ]; then
  for fare in 100 200 150 300 250; do
    http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":$D_EXACT,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":$fare,
 "requestedAt":"2026-03-15T10:00:00","completedAt":"2026-03-15T10:30:00",
 "metadata":{}}
EOF
)"
    R_X="$(echo "$LAST_BODY" | jq -r '.id // empty')"
    [ -n "$R_X" ] && http_auth POST "$PAYMENT_URL/api/payments/ride/$R_X" "$TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"userId\":1,\"amount\":$fare,\"method\":\"CREDIT_CARD\"}" >/dev/null
  done
  sleep 1
  http_auth GET "$BASE/api/drivers/$D_EXACT/dashboard" "$TOKEN"
  if [ "$LAST_STATUS" = "200" ]; then
    tr="$(echo "$LAST_BODY"  | jq -r '.totalRides')"
    te="$(echo "$LAST_BODY"  | jq -r '.totalEarnings')"
    af="$(echo "$LAST_BODY"  | jq -r '.averageRideFare')"
    ar="$(echo "$LAST_BODY"  | jq -r '.averageRating')"
    tt="$(echo "$LAST_BODY"  | jq -r '.totalRatings')"
    # JSON numbers may come back as 1000 or 1000.0 — compare numerically (float-safe)
    num_eq() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a+0==b+0)}'; }
    num_eq "$tr" 5    && pass "§10.2.3.a totalRides=5"        || fail "§10.2.3.a totalRides=5" "got $tr"
    num_eq "$te" 1000 && pass "§10.2.3.a totalEarnings=1000"  || fail "§10.2.3.a totalEarnings=1000" "got $te"
    num_eq "$af" 200  && pass "§10.2.3.a averageRideFare=200" || fail "§10.2.3.a averageRideFare=200" "got $af"
    num_eq "$ar" 4.5  && pass "§10.2.3.a averageRating=4.5"   || fail "§10.2.3.a averageRating=4.5" "got $ar"
    num_eq "$tt" 100  && pass "§10.2.3.a totalRatings=100"    || fail "§10.2.3.a totalRatings=100" "got $tt"
  fi

  # §10.2.3 step c — driver with no rides → totalRides=0, totalEarnings=0
  SALT_Z="${RANDOM}${RANDOM}"
  http_auth POST "$BASE/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Zero Rides","email":"zr-${SALT_Z}@x.io","phone":"+11${SALT_Z:0:9}",
 "licenseNumber":"LIC-ZR-${SALT_Z}","rating":0.0,"totalRatings":0,
 "status":"OFFLINE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"ZR-${SALT_Z}"}}
EOF
)"
  D_ZERO="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  if [ -n "$D_ZERO" ]; then
    http_auth GET "$BASE/api/drivers/$D_ZERO/dashboard" "$TOKEN"
    z_tr="$(echo "$LAST_BODY" | jq -r '.totalRides')"
    z_te="$(echo "$LAST_BODY" | jq -r '.totalEarnings')"
    awk -v a="$z_tr" 'BEGIN{exit !(a+0==0)}' \
      && pass "§10.2.3.c zero-state totalRides=0" \
      || fail "§10.2.3.c zero-state totalRides=0" "got $z_tr"
    awk -v a="$z_te" 'BEGIN{exit !(a+0==0)}' \
      && pass "§10.2.3.c zero-state totalEarnings=0" \
      || fail "§10.2.3.c zero-state totalEarnings=0" "got $z_te"
    http_auth DELETE "$BASE/api/drivers/$D_ZERO" "$TOKEN" >/dev/null
  fi

  # §10.2.2 step b — driver with NO description key in vehicleDetails JSONB
  SALT_N="${RANDOM}${RANDOM}"
  http_auth POST "$BASE/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"No Desc","email":"nd-${SALT_N}@x.io","phone":"+12${SALT_N:0:9}",
 "licenseNumber":"LIC-ND-${SALT_N}","rating":3.5,"totalRatings":1,
 "status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"HATCHBACK","plate":"ND-${SALT_N}"}}
EOF
)"
  D_NODESC="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  if [ -n "$D_NODESC" ]; then
    http_auth POST "$BASE/api/drivers/$D_NODESC/index" "$TOKEN"
    assert_status_in "§10.2.2.b indexing succeeds with empty description" 200 201
    http_auth DELETE "$BASE/api/drivers/$D_NODESC" "$TOKEN" >/dev/null
  fi

  http_auth DELETE "$BASE/api/drivers/$D_EXACT" "$TOKEN" >/dev/null
fi

# 404 unknown driver
http_auth GET "$BASE/api/drivers/9999999/dashboard" "$TOKEN"
assert_status 404 "S2-F12 unknown driver → 404"

# No token
http GET "$BASE/api/drivers/$D_TOYOTA/dashboard"
assert_status 401 "S2-F12 no token → 401"

# Cached for 10 min — and DASHBOARD_VIEWED logged on EVERY invocation (cache hit too)
# §10.2.3.e — "log must be written on every invocation, independently of whether the
# response was served from cache". Take a fresh count, fire 2 calls (1st miss, 2nd hit),
# then poll until the count grows by ≥2 (Observer write-through can lag a beat).
before="$(mongo_count driver_events "{ driverId: $D_TOYOTA, action: 'DASHBOARD_VIEWED' }")"
http_auth GET "$BASE/api/drivers/$D_TOYOTA/dashboard" "$TOKEN" >/dev/null
http_auth GET "$BASE/api/drivers/$D_TOYOTA/dashboard" "$TOKEN" >/dev/null
target=$((before + 2))
after="$before"
for _ in 1 2 3 4 5 6 7 8 9 10; do
  after="$(mongo_count driver_events "{ driverId: $D_TOYOTA, action: 'DASHBOARD_VIEWED' }")"
  [ "${after:-0}" -ge "$target" ] && break
  sleep 1
done
diff=$((after - before))
if [ "$diff" -ge 2 ]; then
  pass "S2-F12 logs DASHBOARD_VIEWED on every call (incl. cache hit) (§10.2.3.e) — +$diff events"
else
  fail "S2-F12 logs DASHBOARD_VIEWED on every call (incl. cache hit) (§10.2.3.e)" "+$diff events (expected ≥2)"
fi

# DASHBOARD_VIEWED must NOT invalidate caches (§4.4.4 "pure observability")
# We rely on the second call not having flushed the entity-detail cache:
[ "$(redis_count_keys "driver-service::driver::$D_TOYOTA")" -ge 1 ] \
  && pass "DASHBOARD_VIEWED does NOT invalidate driver entity cache (§4.4.4 obs-only)" \
  || fail "DASHBOARD_VIEWED does NOT invalidate driver entity cache (§4.4.4 obs-only)"

# ============================================================
# M1 S2 features (smoke pass — JWT, 2xx, cache key shape)
# ============================================================

# S2-F1 search   /api/drivers/search?status=...&minRating=...&maxRating=...
http_auth GET "$BASE/api/drivers/search?status=AVAILABLE&minRating=0&maxRating=5" "$TOKEN"
assert_status 200 "M1 S2-F1 GET /api/drivers/search"
[ "$(redis_count_keys 'driver-service::S2-F1::*')" -ge 1 ] \
  && pass "S2-F1 caches driver-service::S2-F1::*" \
  || fail "S2-F1 caches driver-service::S2-F1::*"

# S2-F3 earnings /api/drivers/{id}/earnings
http_auth GET "$BASE/api/drivers/$D_TOYOTA/earnings?startDate=2026-01-01&endDate=2026-12-31" "$TOKEN"
assert_status_in "M1 S2-F3 GET /api/drivers/{id}/earnings" 200 204

# S2-F5 vehicle-type
http_auth GET "$BASE/api/drivers/vehicle-type?type=SEDAN" "$TOKEN"
assert_status_in "M1 S2-F5 GET /api/drivers/vehicle-type" 200 204

# S2-F6 top-rated
http_auth GET "$BASE/api/drivers/reports/top-rated?limit=5" "$TOKEN"
assert_status_in "M1 S2-F6 GET /api/drivers/reports/top-rated" 200 204

# S2-F9 expired documents
http_auth GET "$BASE/api/drivers/documents/expired" "$TOKEN"
assert_status_in "M1 S2-F9 GET /api/drivers/documents/expired" 200 204

# S2-F2 update vehicle (write — emits VEHICLE_DETAILS_UPDATED, invalidates entity)
http_auth PUT "$BASE/api/drivers/$D_TOYOTA/vehicle" "$TOKEN" -H "Content-Type: application/json" -d '{"vehicleType":"SEDAN","plate":"NEW-PLATE"}'
assert_status_in "M1 S2-F2 PUT /api/drivers/{id}/vehicle" 200 204

# S2-F4 availability (write — emits AVAILABILITY_UPDATED)
http_auth PUT "$BASE/api/drivers/$D_TOYOTA/availability" "$TOKEN" -H "Content-Type: application/json" -d '{"status":"BUSY"}'
assert_status_in "M1 S2-F4 PUT /api/drivers/{id}/availability" 200 204

# S2-F7 rate (write — emits RATING_RECORDED)
http_auth POST "$BASE/api/drivers/$D_TOYOTA/rate" "$TOKEN" -H "Content-Type: application/json" -d '{"rideId":1,"rating":5}'
assert_status_in "M1 S2-F7 POST /api/drivers/{id}/rate" 200 201 204 400 404

# ============================================================
# CRUD DriverDocument   (§4.4.2: get-by-id cached, list NOT cached)
# ============================================================

http_auth POST "$BASE/api/drivers/$D_TOYOTA/documents" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"type":"LICENSE","documentUrl":"https://example.com/dl-$RUN_ID.pdf","expiryDate":"2030-01-01","verified":false,"uploadedAt":"2026-04-01T00:00:00"}
EOF
)"
DOC_ID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
assert_status_in "CRUD POST /api/drivers/{id}/documents" 200 201
if [ -n "$DOC_ID" ]; then
  http_auth GET "$BASE/api/drivers/$D_TOYOTA/documents/$DOC_ID" "$TOKEN"
  assert_status 200 "CRUD GET /api/drivers/{id}/documents/{docId}"
  http_auth GET "$BASE/api/drivers/$D_TOYOTA/documents/$DOC_ID" "$TOKEN" >/dev/null
  [ "$(redis_count_keys "driver-service::driver-document::$DOC_ID")" -ge 1 ] \
    && pass "GET-by-id caches driver-service::driver-document::$DOC_ID" \
    || fail "GET-by-id caches driver-service::driver-document::$DOC_ID"

  # S2-F8 verify document — emits DOCUMENT_VERIFIED. Per M1 spec the verifier must be
  # an ADMIN user; verifiedBy is the admin's user-id (Long), not a free-form string.
  ADMIN_TOKEN_S28="$(login_user "${ADMIN_EMAIL:-admin@uber.com}" "${ADMIN_PASSWORD:-admin123}" 2>/dev/null || true)"
  ADMIN_UID_S28="$(jwt_uid "$ADMIN_TOKEN_S28" 2>/dev/null)"
  if [ -n "$ADMIN_UID_S28" ]; then
    http_auth PUT "$BASE/api/drivers/$D_TOYOTA/documents/$DOC_ID/verify" "$ADMIN_TOKEN_S28" \
      -H "Content-Type: application/json" -d "{\"verifiedBy\":$ADMIN_UID_S28}"
    assert_status_in "M1 S2-F8 PUT verify document" 200 204
  else
    skip "M1 S2-F8 PUT verify document" "no seeded ADMIN reachable; set ADMIN_EMAIL/ADMIN_PASSWORD"
  fi

  http_auth PUT "$BASE/api/drivers/$D_TOYOTA/documents/$DOC_ID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"type":"LICENSE","documentUrl":"https://example.com/dl-$RUN_ID-renewed.pdf","expiryDate":"2031-01-01","verified":true,"uploadedAt":"2026-04-01T00:00:00"}
EOF
)"
  assert_status_in "CRUD PUT /api/drivers/{id}/documents/{docId}" 200 204
  http_auth DELETE "$BASE/api/drivers/$D_TOYOTA/documents/$DOC_ID" "$TOKEN"
  assert_status_in "CRUD DELETE /api/drivers/{id}/documents/{docId}" 200 204
fi

http_auth DELETE "$BASE/api/drivers/$D_TOYOTA" "$TOKEN" >/dev/null
http_auth DELETE "$BASE/api/drivers/$D_SUV"    "$TOKEN" >/dev/null
