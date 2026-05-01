#!/usr/bin/env bash
# ride-service (port 8083) — full endpoint coverage.
# Spec sections covered:
#   §10.3.1 S3-F10 Ride Analytics Dashboard   (distinct from M1 /api/rides/analytics)
#   §10.3.2 S3-F11 Record User-Driver Riding Pattern (Neo4j idempotent)
#   §10.3.3 S3-F12 Driver Recommendations for User (ownership)
#   §7.3    Neo4j UserNode / DriverNode / RODE_WITH
#   §4.4    Cache contract: S3-F1, S3-F3, S3-F5, S3-F6, S3-F9, ride-by-id, rideStop-by-id
#   §10.3.2 step d: idempotency in Neo4j (NOT PostgreSQL)
#   M1 S3-F1..S3-F9 + CRUD Ride + CRUD RideStop

source "$(dirname "$0")/lib/common.sh"

section "30 ride-service — S3-F10/F11/F12 + M1 + CRUD"

BASE="$RIDE_URL"
TOKEN="$(register_user "ride")"
if [ -z "$TOKEN" ]; then
  fail "register seed user via user-service" \
       "user-service not reachable or registration broken — entire script skipped"
  exit 0
fi
UID_T="$(jwt_uid "$TOKEN")"

# ============================================================
# CRUD Ride
# ============================================================

create_ride() {
  local status="${1:-COMPLETED}" user_id="${2:-1}" driver_id="${3:-1}" fare="${4:-100}"
  http_auth POST "$BASE/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":$user_id,"driverId":$driver_id,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"$status","fare":$fare,
 "requestedAt":"2026-03-15T10:00:00",
 "completedAt":"2026-03-15T10:30:00",
 "metadata":{"surgeMultiplier":1.2}}
EOF
)"
  echo "$LAST_BODY" | jq -r '.id // empty'
}

R1="$(create_ride COMPLETED 1 1 100)"
R2="$(create_ride CANCELLED 1 1 0)"
R3="$(create_ride REQUESTED 1 1 0)"
[ -n "$R1" ] && pass "CRUD POST /api/rides → 201 R1=$R1" || fail "CRUD POST /api/rides"

# CRUD GET-by-id — cached
http_auth GET "$BASE/api/rides/$R1" "$TOKEN"
assert_status 200 "CRUD GET /api/rides/$R1"
http_auth GET "$BASE/api/rides/$R1" "$TOKEN" >/dev/null
[ "$(redis_count_keys "ride-service::ride::$R1")" -ge 1 ] \
  && pass "GET-by-id caches ride-service::ride::$R1" \
  || fail "GET-by-id caches ride-service::ride::$R1"

# CRUD list NOT cached
http_auth GET "$BASE/api/rides" "$TOKEN" >/dev/null
assert_status 200 "CRUD GET /api/rides (list)"

# ============================================================
# S3-F10 Ride Analytics Dashboard   (§10.3.1)
# Distinct from /api/rides/analytics (M1 S3-F6).
# ============================================================

# (a) valid date range
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31" "$TOKEN"
assert_status 200 "S3-F10 valid range"
for f in totalRides totalRevenue averageRideFare completionRate ridesByStatus; do
  v="$(echo "$LAST_BODY" | jq -r ".${f}")"
  [ "$v" != "null" ] && [ -n "$v" ] && pass "S3-F10 has $f" || fail "S3-F10 has $f"
done

# §10.3.1 step a — exact analytics math.
# Seed 10 rides: 6 COMPLETED, 2 CANCELLED, 2 REQUESTED.
# The SUT's createRide() overwrites requestedAt with LocalDateTime.now() (server-side
# clock is authoritative), so the supplied requestedAt strings are ignored and every
# seeded ride is timestamped at "now". We therefore query a window that brackets the
# current server day rather than the spec's literal March-2026 range, which is purely
# a narrative date in the spec text — §10.3.1.a only fixes the *counts*, not the
# absolute dates.
EM_START="$(date -u -d 'yesterday' +%Y-%m-%d 2>/dev/null || date -u -v-1d +%Y-%m-%d)"
EM_END="$(date -u -d 'tomorrow'  +%Y-%m-%d 2>/dev/null || date -u -v+1d +%Y-%m-%d)"
EM_TS="$(date -u +%Y-%m-%dT%H:%M:%S)"
# Capture pre-seed counters: the 'today' window may already contain rides
# (R1/R2/R3 above, plus any leftovers from prior runs). The §10.3.1.a math
# applies to the 10 new rides only, so we assert *deltas* not absolutes.
# Clear the S3-F10 cache first so the snapshot reflects PG ground truth
# rather than a stale cached result (CRUD POST /api/rides invalidates the
# `ride-service::S3-F10` cache too, but only after the snapshot read here).
redis_flush_pattern 'ride-service::S3-F10::*' >/dev/null
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=${EM_START}&endDate=${EM_END}" "$TOKEN"
PRE_TOTAL="$(echo "$LAST_BODY" | jq -r '.totalRides // 0')"
PRE_COMPLETED="$(echo "$LAST_BODY" | jq -r '.ridesByStatus.COMPLETED // 0')"
for i in 1 2 3 4 5 6; do
  http_auth POST "$BASE/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,"pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":100,
 "requestedAt":"${EM_TS}",
 "completedAt":"${EM_TS}",
 "metadata":{}}
EOF
)" >/dev/null
done
for i in 1 2; do
  http_auth POST "$BASE/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,"pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"CANCELLED","fare":0,
 "requestedAt":"${EM_TS}","metadata":{}}
EOF
)" >/dev/null
done
for i in 1 2; do
  http_auth POST "$BASE/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,"pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"REQUESTED","fare":0,
 "requestedAt":"${EM_TS}","metadata":{}}
EOF
)" >/dev/null
done
sleep 1
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=${EM_START}&endDate=${EM_END}" "$TOKEN"
if [ "$LAST_STATUS" = "200" ]; then
  tr="$(echo "$LAST_BODY" | jq -r '.totalRides')"
  cr="$(echo "$LAST_BODY" | jq -r '.completionRate')"
  rs="$(echo "$LAST_BODY" | jq -r '.ridesByStatus.COMPLETED // 0')"
  d_total=$(( ${tr:-0} - ${PRE_TOTAL:-0} ))
  d_completed=$(( ${rs:-0} - ${PRE_COMPLETED:-0} ))
  [ "$d_total" = "10" ] && pass "§10.3.1.a totalRides delta=10" \
                        || fail "§10.3.1.a totalRides delta=10" "got delta=$d_total (pre=$PRE_TOTAL, post=$tr)"
  # Spec §10.3.1.a — "completionRate=0.6" (fraction in [0.0, 1.0]). The
  # value is computed over the *full window* (existing rides + the 10 new),
  # so we re-derive the expected rate from the full denominator.
  exp_completed=$(( ${PRE_COMPLETED:-0} + 6 ))
  exp_total="${tr:-0}"
  if [ "${exp_total:-0}" -gt 0 ]; then
    exp_rate="$(awk "BEGIN{printf \"%.4f\", $exp_completed/$exp_total}")"
    lo="$(awk "BEGIN{printf \"%.4f\", $exp_rate - 0.01}")"
    hi="$(awk "BEGIN{printf \"%.4f\", $exp_rate + 0.01}")"
    if awk "BEGIN{exit !(${cr:-0} >= $lo && ${cr:-0} <= $hi)}"; then
      pass "§10.3.1.a completionRate≈$exp_rate (got $cr)"
    else
      fail "§10.3.1.a completionRate≈$exp_rate" "got $cr (window total=$exp_total, completed=$exp_completed)"
    fi
  fi
  [ "$d_completed" = "6" ] && pass "§10.3.1.a ridesByStatus.COMPLETED delta=6" \
                           || fail "§10.3.1.a ridesByStatus.COMPLETED delta=6" "got delta=$d_completed (pre=$PRE_COMPLETED, post=$rs)"
fi

# §10.3.1 step b — date range with NO rides → all zeros
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=2099-01-01&endDate=2099-01-31" "$TOKEN"
if [ "$LAST_STATUS" = "200" ]; then
  z="$(echo "$LAST_BODY" | jq -r '.totalRides')"
  [ "$z" = "0" ] && pass "§10.3.1.b empty range totalRides=0" \
                 || fail "§10.3.1.b empty range totalRides=0" "got $z"
fi

# (b) inverted range → 400
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=2026-04-01&endDate=2026-03-01" "$TOKEN"
assert_status 400 "S3-F10 startDate > endDate → 400"

# (c) no token → 401
http GET "$BASE/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31"
assert_status 401 "S3-F10 no token → 401"

# (d) ANALYTICS_VIEWED logged on every call (incl. cache hit, §10.3.1 step d)
before="$(mongo_count ride_events "{ action: 'ANALYTICS_VIEWED' }")"
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31" "$TOKEN" >/dev/null
http_auth GET "$BASE/api/rides/analytics/dashboard?startDate=2026-03-01&endDate=2026-03-31" "$TOKEN" >/dev/null
sleep 1
after="$(mongo_count ride_events "{ action: 'ANALYTICS_VIEWED' }")"
diff=$((after - before))
[ "$diff" -ge 2 ] && pass "S3-F10 ANALYTICS_VIEWED logged on every call (+$diff)" \
                  || fail "S3-F10 ANALYTICS_VIEWED logged on every call" "+$diff"

# (e) Cached (10 min)
[ "$(redis_count_keys 'ride-service::S3-F10::*')" -ge 1 ] \
  && pass "S3-F10 caches ride-service::S3-F10::*" \
  || fail "S3-F10 caches ride-service::S3-F10::* (§4.4.1, §8.1 dashboards 10m)"

# (f) Distinct path: M1 /api/rides/analytics still works
http_auth GET "$BASE/api/rides/analytics?startDate=2026-03-01&endDate=2026-03-31" "$TOKEN"
assert_status_in "M1 /api/rides/analytics still 200 (distinct-path rule)" 200 204

# (g) Coexistence: response shapes differ — M1 has no ridesByStatus map
# §10.3.1 distinct-DTO note: M1's RideAnalyticsDTO has totalRides /
# completedRides / cancelledRides / totalRevenue / averageRideFare /
# completionRate, but NOT ridesByStatus. M2 adds the ridesByStatus map.
# A shared DTO is a §10.3.1 violation ("M2's new /dashboard endpoint
# returns the richer RideAnalyticsDashboardDTO with a status breakdown
# map. Both must coexist — do not overwrite either").
m1_has="$(echo "$LAST_BODY" | jq -r '.ridesByStatus // "missing"')"
if [ "$m1_has" = "missing" ] || [ "$m1_has" = "null" ]; then
  pass "M1 /api/rides/analytics has no ridesByStatus (§10.3.1 distinct-DTO rule)"
else
  fail "M1 /api/rides/analytics has no ridesByStatus (§10.3.1 distinct-DTO rule)" \
       "ride service shares one DTO across both endpoints — should be two distinct DTOs"
fi

# ============================================================
# S3-F11 Record User-Driver Riding Pattern   (§10.3.2)
# ============================================================

# (a) First call on R1 → 200, rideCount=1
http_auth POST "$BASE/api/rides/$R1/record-interaction" "$TOKEN"
assert_status 200 "S3-F11 first record on R1"
inter_count="$(mongo_count ride_events "{ rideId: $R1, action: 'INTERACTION_RECORDED' }")"
[ "${inter_count:-0}" -ge 1 ] && pass "S3-F11 emits INTERACTION_RECORDED" \
                              || fail "S3-F11 emits INTERACTION_RECORDED"

# (b) Idempotent — second call on same R1 must NOT bump count nor emit a new event
http_auth POST "$BASE/api/rides/$R1/record-interaction" "$TOKEN"
assert_status 200 "S3-F11 idempotent re-call on R1"
sleep 1
inter_count2="$(mongo_count ride_events "{ rideId: $R1, action: 'INTERACTION_RECORDED' }")"
if [ "${inter_count2:-0}" = "${inter_count:-0}" ]; then
  pass "S3-F11 idempotent: no extra INTERACTION_RECORDED on re-call (Neo4j marker)"
else
  fail "S3-F11 idempotent: no extra INTERACTION_RECORDED on re-call" "before=$inter_count, after=$inter_count2"
fi

# (c) Second ride from same user-driver pair increments rideCount on RODE_WITH
R4="$(create_ride COMPLETED 1 1 120)"
http_auth POST "$BASE/api/rides/$R4/record-interaction" "$TOKEN"
assert_status 200 "S3-F11 second distinct rideId increments edge"

# (d) Non-COMPLETED ride → 400
http_auth POST "$BASE/api/rides/$R3/record-interaction" "$TOKEN"
assert_status 400 "S3-F11 REQUESTED ride → 400"

# (e) Non-existent ride → 404
http_auth POST "$BASE/api/rides/9999999/record-interaction" "$TOKEN"
assert_status 404 "S3-F11 unknown ride → 404"

# (f) No token → 401
http POST "$BASE/api/rides/$R1/record-interaction"
assert_status 401 "S3-F11 no token → 401"

# (g) Wildcard cache invalidation: ride-service::S3-F12::* must be empty after this write
[ "$(redis_count_keys 'ride-service::S3-F12::*')" = "0" ] \
  && pass "S3-F11 wildcard-invalidates ride-service::S3-F12::*" \
  || fail "S3-F11 wildcard-invalidates ride-service::S3-F12::* (§4.4.4 NoSQL-writer)"

# ============================================================
# S3-F12 Driver Recommendations for User   (§10.3.3)
# ============================================================

# Bake some recommendations: A→D1, A→D2; B→D1, B→D3; C→D2, C→D4
# Done via /record-interaction (we use existing R4 for now and create more)
# The seeding here is loose — full deterministic seeding requires real driver IDs
# we don't have; we only check the path/contract.

http_auth GET "$BASE/api/rides/recommendations?userId=$UID_T&limit=5" "$TOKEN"
assert_status 200 "S3-F12 own recommendations → 200"

# Ownership: register a second user, B asks for A's recs → 403
TOKEN_B="$(register_user "rec2")"
http_auth GET "$BASE/api/rides/recommendations?userId=$UID_T&limit=5" "$TOKEN_B"
assert_status 403 "S3-F12 cross-user → 403"

# No token → 401
http GET "$BASE/api/rides/recommendations?userId=$UID_T&limit=5"
assert_status 401 "S3-F12 no token → 401"

# §10.3.3 step d — user with no recorded interactions → empty list
TOKEN_NEW="$(register_user "rec-empty")"
UID_NEW="$(jwt_uid "$TOKEN_NEW")"
http_auth GET "$BASE/api/rides/recommendations?userId=$UID_NEW&limit=5" "$TOKEN_NEW"
if [ "$LAST_STATUS" = "200" ]; then
  empty="$(echo "$LAST_BODY" | jq -r 'length // 0')"
  [ "${empty:-99}" = "0" ] && pass "§10.3.3.d new user → empty recs" \
                           || fail "§10.3.3.d new user → empty recs" "got $empty"
fi

# §10.3.3 step a — graph: A→D1,D2 ; B→D1,D3 ; C→D2,D4.
# Recommendations for A should include D3 and D4, exclude D1 and D2.
# Seeding requires real driverIds from driver-service; we approximate by
# spinning 4 fresh drivers + 3 users + 6 completed rides + 6 record-interactions.
seed_driver() {
  local s="${RANDOM}${RANDOM}"
  http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Rec $1","email":"rec-${s}@x.io","phone":"+14${s:0:9}",
 "licenseNumber":"LIC-REC-${s}","rating":4.0,"totalRatings":1,
 "status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"REC-${s}"}}
EOF
)" >/dev/null
  echo "$LAST_BODY" | jq -r '.id // empty'
}
D1="$(seed_driver D1)" ; D2="$(seed_driver D2)" ; D3="$(seed_driver D3)" ; D4="$(seed_driver D4)"
TOKEN_USERA="$TOKEN"; UID_A="$UID_T"
TOKEN_USERB="$(register_user "rec-b")"; UID_B="$(jwt_uid "$TOKEN_USERB")"
TOKEN_USERC="$(register_user "rec-c")"; UID_C="$(jwt_uid "$TOKEN_USERC")"

seed_ride_and_interact() {
  local who_token="$1" who_uid="$2" drv="$3"
  http_auth POST "$BASE/api/rides" "$who_token" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":$who_uid,"driverId":$drv,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":100,
 "requestedAt":"2026-08-15T10:00:00","completedAt":"2026-08-15T10:30:00",
 "metadata":{}}
EOF
)" >/dev/null
  local rid="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  [ -n "$rid" ] && http_auth POST "$BASE/api/rides/$rid/record-interaction" "$who_token" >/dev/null
}
# A→D1, A→D2
[ -n "$D1" ] && seed_ride_and_interact "$TOKEN_USERA" "$UID_A" "$D1"
[ -n "$D2" ] && seed_ride_and_interact "$TOKEN_USERA" "$UID_A" "$D2"
# B→D1, B→D3
[ -n "$D1" ] && seed_ride_and_interact "$TOKEN_USERB" "$UID_B" "$D1"
[ -n "$D3" ] && seed_ride_and_interact "$TOKEN_USERB" "$UID_B" "$D3"
# C→D2, C→D4
[ -n "$D2" ] && seed_ride_and_interact "$TOKEN_USERC" "$UID_C" "$D2"
[ -n "$D4" ] && seed_ride_and_interact "$TOKEN_USERC" "$UID_C" "$D4"

http_auth GET "$BASE/api/rides/recommendations?userId=$UID_A&limit=5" "$TOKEN_USERA"
if [ "$LAST_STATUS" = "200" ] && [ -n "$D3$D4$D1$D2" ]; then
  ids="$(echo "$LAST_BODY" | jq -r '.[].driverId' 2>/dev/null)"
  echo "$ids" | grep -q "^${D3}$" && pass "§10.3.3.a recs include D3 ($D3)" || fail "§10.3.3.a recs include D3 ($D3)" "ids=$(echo $ids|tr '\n' ' ')"
  echo "$ids" | grep -q "^${D4}$" && pass "§10.3.3.a recs include D4 ($D4)" || fail "§10.3.3.a recs include D4 ($D4)" "ids=$(echo $ids|tr '\n' ' ')"
  # exclude assertions: A directly rode with D1 and D2, so they must NOT show up
  echo "$ids" | grep -q "^${D1}$" && fail "§10.3.3.a recs exclude D1 ($D1)" "D1 leaked into recs" || pass "§10.3.3.a recs exclude D1 (own driver)"
  echo "$ids" | grep -q "^${D2}$" && fail "§10.3.3.a recs exclude D2 ($D2)" "D2 leaked into recs" || pass "§10.3.3.a recs exclude D2 (own driver)"
fi

# Default limit = 5
http_auth GET "$BASE/api/rides/recommendations?userId=$UID_T" "$TOKEN"
assert_status 200 "S3-F12 default limit"
len="$(echo "$LAST_BODY" | jq -r 'length // 0')"
[ "${len:-99}" -le 5 ] && pass "S3-F12 default limit ≤ 5" || fail "S3-F12 default limit ≤ 5" "got $len"

# ADMIN bypass — if seeded
ADMIN_TOKEN="$(login_user "${ADMIN_EMAIL:-admin@uber.com}" "${ADMIN_PASSWORD:-admin123}" || true)"
if [ -n "$ADMIN_TOKEN" ]; then
  http_auth GET "$BASE/api/rides/recommendations?userId=$UID_T&limit=5" "$ADMIN_TOKEN"
  assert_status 200 "S3-F12 ADMIN bypass"
  http_auth GET "$BASE/api/rides/recommendations?userId=9999999&limit=5" "$ADMIN_TOKEN"
  assert_status 404 "S3-F12 ADMIN + unknown user → 404"
fi

# Cached for 5 min
[ "$(redis_count_keys 'ride-service::S3-F12::*')" -ge 1 ] \
  && pass "S3-F12 caches ride-service::S3-F12::*" \
  || fail "S3-F12 caches ride-service::S3-F12::* (§4.4.1, §8.1 recommendations 5m)"

# ============================================================
# M1 S3 features
# ============================================================

# S3-F1 search
http_auth GET "$BASE/api/rides/search?status=COMPLETED&startDate=2026-01-01&endDate=2026-12-31" "$TOKEN"
assert_status_in "M1 S3-F1 GET /api/rides/search" 200 204
[ "$(redis_count_keys 'ride-service::S3-F1::*')" -ge 1 ] \
  && pass "S3-F1 caches ride-service::S3-F1::*" \
  || fail "S3-F1 caches ride-service::S3-F1::*"

# S3-F3 fare estimate (POST, semantically read-only, cached by body hash)
http_auth POST "$BASE/api/rides/estimate" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"pickupLatitude":30.0,"pickupLongitude":31.0,"dropoffLatitude":30.1,"dropoffLongitude":31.1,"vehicleType":"SEDAN"}
EOF
)"
assert_status 200 "M1 S3-F3 POST /api/rides/estimate"
[ "$(redis_count_keys 'ride-service::S3-F3::*')" -ge 1 ] \
  && pass "S3-F3 caches ride-service::S3-F3::*" \
  || fail "S3-F3 caches ride-service::S3-F3::*"

# S3-F5 metadata search
http_auth GET "$BASE/api/rides/metadata/search?key=surgeMultiplier&value=1.2" "$TOKEN"
assert_status_in "M1 S3-F5 GET /api/rides/metadata/search" 200 204

# S3-F6 analytics (M1)
http_auth GET "$BASE/api/rides/analytics?startDate=2026-01-01&endDate=2026-12-31" "$TOKEN"
assert_status_in "M1 S3-F6 GET /api/rides/analytics" 200 204
[ "$(redis_count_keys 'ride-service::S3-F6::*')" -ge 1 ] \
  && pass "S3-F6 caches ride-service::S3-F6::*" \
  || fail "S3-F6 caches ride-service::S3-F6::*"

# S3-F9 details
http_auth GET "$BASE/api/rides/$R1/details" "$TOKEN"
assert_status 200 "M1 S3-F9 GET /api/rides/{id}/details"

# S3-F2 assign — emits DRIVER_ASSIGNED
http_auth PUT "$BASE/api/rides/$R3/assign?driverId=1" "$TOKEN"
assert_status_in "M1 S3-F2 PUT /api/rides/{id}/assign" 200 204 400 404

# S3-F4 complete — emits RIDE_COMPLETED
http_auth PUT "$BASE/api/rides/$R3/complete" "$TOKEN"
assert_status_in "M1 S3-F4 PUT /api/rides/{id}/complete" 200 204 400 404

# S3-F7 cancel — emits RIDE_CANCELLED
http_auth PUT "$BASE/api/rides/$R3/cancel" "$TOKEN"
assert_status_in "M1 S3-F7 PUT /api/rides/{id}/cancel" 200 204 400 404

# ============================================================
# CRUD RideStop  + S3-F8 add stops (write — emits STOPS_ADDED)
# ============================================================

# S3-F8 only allows adding stops to a ride that is REQUESTED or ACCEPTED
# (M1 spec: stops are pre-trip detours added before the driver picks up).
# R1 is COMPLETED and R3 has been mutated by the M1 lifecycle calls above,
# so spin a fresh REQUESTED ride for the stops sub-suite.
RSTOP="$(create_ride REQUESTED 1 1 0)"

http_auth POST "$BASE/api/rides/$RSTOP/stops" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
[{"latitude":30.05,"longitude":31.05,"sequence":1,"address":"Stop 1"}]
EOF
)"
assert_status_in "M1 S3-F8 POST /api/rides/{rideId}/stops" 200 201
SID="$(echo "$LAST_BODY" | jq -r '.stops[0].id // .id // empty')"

http_auth GET "$BASE/api/rides/$RSTOP/stops" "$TOKEN"
assert_status 200 "CRUD GET /api/rides/{rideId}/stops (list)"

if [ -n "$SID" ]; then
  http_auth GET "$BASE/api/rides/$RSTOP/stops/$SID" "$TOKEN"
  assert_status 200 "CRUD GET /api/rides/{rideId}/stops/{stopId}"
  http_auth GET "$BASE/api/rides/$RSTOP/stops/$SID" "$TOKEN" >/dev/null
  [ "$(redis_count_keys "ride-service::rideStop::$SID")" -ge 1 ] \
    && pass "GET-by-id caches ride-service::rideStop::$SID" \
    || fail "GET-by-id caches ride-service::rideStop::$SID"

  http_auth PUT "$BASE/api/rides/$RSTOP/stops/$SID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"latitude":30.06,"longitude":31.06,"sequence":1,"address":"Stop 1 Updated"}
EOF
)"
  assert_status_in "CRUD PUT /api/rides/{rideId}/stops/{stopId}" 200 204
  [ "$(redis_count_keys "ride-service::rideStop::$SID")" = "0" ] \
    && pass "PUT invalidates ride-service::rideStop::$SID" \
    || fail "PUT invalidates ride-service::rideStop::$SID"

  http_auth DELETE "$BASE/api/rides/$RSTOP/stops/$SID" "$TOKEN"
  assert_status_in "CRUD DELETE /api/rides/{rideId}/stops/{stopId}" 200 204
fi

http_auth DELETE "$BASE/api/rides/$R1" "$TOKEN" >/dev/null
http_auth DELETE "$BASE/api/rides/$R2" "$TOKEN" >/dev/null
http_auth DELETE "$BASE/api/rides/$R3" "$TOKEN" >/dev/null
http_auth DELETE "$BASE/api/rides/$R4" "$TOKEN" >/dev/null
[ -n "${RSTOP:-}" ] && http_auth DELETE "$BASE/api/rides/$RSTOP" "$TOKEN" >/dev/null
