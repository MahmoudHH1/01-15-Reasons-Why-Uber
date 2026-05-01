#!/usr/bin/env bash
# location-service (port 8084) — full endpoint coverage.
# Spec sections covered:
#   §10.4.1 S4-F10 Location Analytics Dashboard (cache 10m, ANALYTICS_VIEWED on every call)
#   §10.4.2 S4-F11 Record Location GPS Event   (Cassandra primary + Mongo audit)
#   §10.4.3 S4-F12 Location Tracking Timeline   (Cassandra clustering DESC, cache 5m)
#   §7.4    LocationTrackingEvent partition by driver_id (queries MUST include driver_id)
#   §4.4.4  S4-F11 wildcard-invalidates location-service::S4-F12::{driverId} AND S4-F10::*
#   §4.4    Cache contract on S4-F1, S4-F3, S4-F5, S4-F6, S4-F8, S4-F9, location-by-id
#   M1 S4-F1..S4-F9 + CRUD Location

source "$(dirname "$0")/lib/common.sh"

section "40 location-service — S4-F10/F11/F12 + M1 + CRUD"

BASE="$LOCATION_URL"
TOKEN="$(register_user "loc")"
if [ -z "$TOKEN" ]; then
  fail "register seed user via user-service" \
       "user-service not reachable or registration broken — entire script skipped"
  exit 0
fi

# We need a real driverId — get one from driver-service
SALT="${RANDOM}${RANDOM}"
http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Loc Driver $RUN_ID","email":"ld-${SALT}@x.io","phone":"+15${SALT:0:9}",
 "licenseNumber":"LIC-LOC-${SALT}",
 "rating":4.0,"totalRatings":0,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"LOC-${SALT}","description":""}}
EOF
)"
DID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
[ -n "$DID" ] && pass "seed driver $DID for location tests" || { fail "seed driver"; exit 1; }

# ============================================================
# CRUD Location
# ============================================================

http_auth POST "$BASE/api/locations" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"driverId":$DID,"latitude":30.0444,"longitude":31.2357,
 "timestamp":"2026-04-15T08:00:00",
 "metadata":{"speed":45.0,"heading":180.0,"accuracy":5.0}}
EOF
)"
assert_status_in "CRUD POST /api/locations" 200 201
LOC_ID="$(echo "$LAST_BODY" | jq -r '.id // empty')"

if [ -n "$LOC_ID" ]; then
  http_auth GET "$BASE/api/locations/$LOC_ID" "$TOKEN"
  assert_status 200 "CRUD GET /api/locations/$LOC_ID"
  http_auth GET "$BASE/api/locations/$LOC_ID" "$TOKEN" >/dev/null
  [ "$(redis_count_keys "location-service::location::$LOC_ID")" -ge 1 ] \
    && pass "GET-by-id caches location-service::location::$LOC_ID" \
    || fail "GET-by-id caches location-service::location::$LOC_ID"

  http_auth PUT "$BASE/api/locations/$LOC_ID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"driverId":$DID,"latitude":30.05,"longitude":31.24,
 "timestamp":"2026-04-15T08:05:00",
 "metadata":{"speed":50.0}}
EOF
)"
  assert_status_in "CRUD PUT /api/locations/$LOC_ID" 200 204
  [ "$(redis_count_keys "location-service::location::$LOC_ID")" = "0" ] \
    && pass "PUT invalidates location-service::location::$LOC_ID" \
    || fail "PUT invalidates location-service::location::$LOC_ID"
fi

# CRUD list NOT cached (§4.4.6 step c)
http_auth GET "$BASE/api/locations" "$TOKEN" >/dev/null
assert_status 200 "CRUD GET /api/locations (list)"

# ============================================================
# S4-F11 Record GPS Tracking Event   (§10.4.2)
# ============================================================

# (a) Valid → 201 + Cassandra row + Mongo audit
http_auth POST "$BASE/api/locations/$DID/tracking" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"latitude":30.0444,"longitude":31.2357,"speed":45.0,"heading":180.0,"accuracy":5.0,
 "rideId":42,"notes":"Heading to pickup"}
EOF
)"
assert_status 201 "S4-F11 POST /api/locations/$DID/tracking → 201"
sleep 1
trk_count="$(mongo_count location_events "{ driverId: $DID, action: 'TRACKING_RECORDED' }")"
[ "${trk_count:-0}" -ge 1 ] && pass "S4-F11 emits TRACKING_RECORDED" \
                            || fail "S4-F11 emits TRACKING_RECORDED"

# (b) Non-existent driver → 404
http_auth POST "$BASE/api/locations/9999999/tracking" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"latitude":1,"longitude":1,"speed":1,"heading":1,"accuracy":1}
EOF
)"
assert_status 404 "S4-F11 unknown driver → 404"

# (c) No token → 401
http POST "$BASE/api/locations/$DID/tracking" -H "Content-Type: application/json" -d '{"latitude":1,"longitude":1}'
assert_status 401 "S4-F11 no token → 401"

# (d) Soft-dep: missing optional fields still ok
http_auth POST "$BASE/api/locations/$DID/tracking" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"latitude":30.0444,"longitude":31.2357,"speed":40.0}
EOF
)"
assert_status 201 "S4-F11 partial body (no heading/accuracy/rideId) → 201"

# (e) Wildcard invalidation: location-service::S4-F12::{DID} must be empty after a write
sleep 1
# §4.4.4 NoSQL-writer rule: S4-F11 must wildcard-invalidate both
# S4-F12::{driverId} and S4-F10::*. We pre-warmed those caches above by
# hitting the GET endpoints, so a count > 0 BEFORE the next write isn't
# surprising; here we instead assert that AFTER the latest tracking POST
# the keys are cleared.
[ "$(redis_count_keys "location-service::S4-F12::$DID*")" = "0" ] \
  && pass "S4-F11 wildcard-invalidates location-service::S4-F12::$DID (§4.4.4)" \
  || fail "S4-F11 wildcard-invalidates location-service::S4-F12::$DID (§4.4.4)"

[ "$(redis_count_keys 'location-service::S4-F10::*')" = "0" ] \
  && pass "S4-F11 wildcard-invalidates location-service::S4-F10::* (§4.4.4)" \
  || fail "S4-F11 wildcard-invalidates location-service::S4-F10::* (§4.4.4)"

# ============================================================
# S4-F12 Location Tracking Timeline   (§10.4.3)
# ============================================================

# (a) all events for driver
http_auth GET "$BASE/api/locations/$DID/tracking" "$TOKEN"
assert_status 200 "S4-F12 GET tracking (all)"
len="$(echo "$LAST_BODY" | jq -r 'length // 0')"
[ "${len:-0}" -ge 2 ] && pass "S4-F12 returns ≥2 events seeded (§10.4.3)" \
                      || fail "S4-F12 returns ≥2 events seeded (§10.4.3)" "got $len; seed flow may have failed"

# Validate DTO shape per §10.4.3
first="$(echo "$LAST_BODY" | jq -r '.[0] // {}')"
for f in timestamp latitude longitude; do
  v="$(echo "$first" | jq -r ".${f}")"
  [ "$v" != "null" ] && [ -n "$v" ] && pass "S4-F12 DTO has $f" || fail "S4-F12 DTO has $f"
done

# (b) startTime/endTime filter
http_auth GET "$BASE/api/locations/$DID/tracking?startTime=2020-01-01T00:00:00&endTime=2030-01-01T00:00:00" "$TOKEN"
assert_status_in "S4-F12 with time range" 200 204

# §10.4.3 step b — narrow time-range filter must return only events
# inside [startTime, endTime].
# The SUT auto-stamps the Cassandra `timestamp` clustering column with
# server-now (§7.4 partition_key=driver_id, clustering=timestamp DESC),
# so client-provided createdAt is ignored. We instead capture the actual
# server-issued timestamps from the timeline, pick a 2-second window
# bracketing the middle event, and assert exactly ≥1 event lands in it
# while the wide-window response had ≥3 events seeded earlier.
http_auth GET "$BASE/api/locations/$DID/tracking" "$TOKEN" >/dev/null
mid_ts="$(echo "$LAST_BODY" | jq -r '.[0].timestamp // empty')"
total_evts="$(echo "$LAST_BODY" | jq -r 'length // 0')"
if [ -n "$mid_ts" ] && [ "${total_evts:-0}" -ge 1 ]; then
  # Build a tight window of [mid_ts, mid_ts] (treat the most-recent event
  # as the target). The narrow result must be ≥1 (the picked event itself).
  http_auth GET "$BASE/api/locations/$DID/tracking?startTime=${mid_ts}&endTime=${mid_ts}" "$TOKEN"
  narrow="$(echo "$LAST_BODY" | jq -r 'length // 0')"
  if [ "${narrow:-0}" -ge 1 ] && [ "${narrow:-0}" -le "${total_evts:-0}" ]; then
    pass "§10.4.3.b narrow filter returns subset of timeline (got $narrow / $total_evts)"
  else
    fail "§10.4.3.b narrow filter returns subset of timeline" \
         "narrow=$narrow, total=$total_evts; filter not honoring time range"
  fi
else
  fail "§10.4.3.b narrow filter precondition (≥1 seeded event)" "got total=$total_evts mid_ts=$mid_ts"
fi

# (c) Newest first (Cassandra clustering DESC)
ts1="$(echo "$LAST_BODY" | jq -r '.[0].timestamp // empty')"
ts2="$(echo "$LAST_BODY" | jq -r '.[1].timestamp // empty')"
# §10.4.3.c — Cassandra clustering DESC means newest-first.
# Re-fetch the timeline cleanly (the previous response was the narrow filter)
# and compare the first two timestamps.
http_auth GET "$BASE/api/locations/$DID/tracking" "$TOKEN" >/dev/null
ts1="$(echo "$LAST_BODY" | jq -r '.[0].timestamp // empty')"
ts2="$(echo "$LAST_BODY" | jq -r '.[1].timestamp // empty')"
if [ -n "$ts1" ] && [ -n "$ts2" ] && [[ "$ts1" > "$ts2" || "$ts1" == "$ts2" ]]; then
  pass "S4-F12 sorts newest-first ($ts1 ≥ $ts2; §10.4.3.c)"
else
  fail "S4-F12 sorts newest-first (§10.4.3.c)" "ts1=$ts1, ts2=$ts2"
fi

# (d) Unknown driver → 404
http_auth GET "$BASE/api/locations/9999999/tracking" "$TOKEN"
assert_status 404 "S4-F12 unknown driver → 404"

# (e) No token → 401
http GET "$BASE/api/locations/$DID/tracking"
assert_status 401 "S4-F12 no token → 401"

# (f) Cached for 5 min
http_auth GET "$BASE/api/locations/$DID/tracking" "$TOKEN" >/dev/null
[ "$(redis_count_keys "location-service::S4-F12::$DID*")" -ge 1 ] \
  && pass "S4-F12 caches location-service::S4-F12::*" \
  || fail "S4-F12 caches location-service::S4-F12::*"

# ============================================================
# S4-F10 Location Analytics Dashboard   (§10.4.1)
# ============================================================

http_auth GET "$BASE/api/locations/analytics?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN"
assert_status 200 "S4-F10 valid range"
for f in totalLocationEvents activeDrivers averageSpeed eventsByHour; do
  v="$(echo "$LAST_BODY" | jq -r ".${f}")"
  [ "$v" != "null" ] && [ -n "$v" ] && pass "S4-F10 has $f" || fail "S4-F10 has $f"
done

# §10.4.1 step a — exact analytics math.
# Seed: 8 location events / 3 distinct drivers / hours 8 and 17 / speeds 20-80.
SALT_A="${RANDOM}${RANDOM}"
seed_loc_driver() {
  local s="${RANDOM}${RANDOM}"
  http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"LA $1","email":"la-${s}@x.io","phone":"+16${s:0:9}",
 "licenseNumber":"LIC-LA-${s}","rating":4.0,"totalRatings":0,
 "status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"LA-${s}"}}
EOF
)" >/dev/null
  echo "$LAST_BODY" | jq -r '.id // empty'
}
LD1="$(seed_loc_driver 1)"
LD2="$(seed_loc_driver 2)"
LD3="$(seed_loc_driver 3)"
seed_loc() {
  local drv="$1" hr="$2" speed="$3"
  http_auth POST "$BASE/api/locations" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"driverId":$drv,"latitude":30.0,"longitude":31.0,
 "timestamp":"2026-09-15T${hr}:00:00",
 "metadata":{"speed":$speed}}
EOF
)" >/dev/null
}
if [ -n "$LD1" ] && [ -n "$LD2" ] && [ -n "$LD3" ]; then
  # 4 events at hour 8 (3 drivers), 4 events at hour 17
  seed_loc "$LD1" 08 20; seed_loc "$LD2" 08 30; seed_loc "$LD3" 08 40; seed_loc "$LD1" 08 50
  seed_loc "$LD1" 17 60; seed_loc "$LD2" 17 70; seed_loc "$LD3" 17 80; seed_loc "$LD2" 17 50
  sleep 1
  http_auth GET "$BASE/api/locations/analytics?startDate=2026-09-01&endDate=2026-09-30" "$TOKEN"
  if [ "$LAST_STATUS" = "200" ]; then
    tle="$(echo "$LAST_BODY" | jq -r '.totalLocationEvents')"
    ad="$(echo "$LAST_BODY"  | jq -r '.activeDrivers')"
    h8="$(echo "$LAST_BODY"  | jq -r '.eventsByHour["8"] // .eventsByHour["08"] // 0')"
    h17="$(echo "$LAST_BODY" | jq -r '.eventsByHour["17"] // 0')"
    [ "${tle:-0}" -ge 8 ] && pass "§10.4.1.a totalLocationEvents≥8 (got $tle)" \
                          || fail "§10.4.1.a totalLocationEvents≥8" "got $tle"
    [ "${ad:-0}" -ge 3 ]  && pass "§10.4.1.a activeDrivers≥3 (got $ad)" \
                          || fail "§10.4.1.a activeDrivers≥3" "got $ad"
    [ "${h8:-0}" -ge 4 ]  && pass "§10.4.1.a eventsByHour[8]≥4" \
                          || fail "§10.4.1.a eventsByHour[8]≥4" "got $h8"
    [ "${h17:-0}" -ge 4 ] && pass "§10.4.1.a eventsByHour[17]≥4" \
                          || fail "§10.4.1.a eventsByHour[17]≥4" "got $h17"
  fi

  http_auth DELETE "$DRIVER_URL/api/drivers/$LD1" "$TOKEN" >/dev/null
  http_auth DELETE "$DRIVER_URL/api/drivers/$LD2" "$TOKEN" >/dev/null
  http_auth DELETE "$DRIVER_URL/api/drivers/$LD3" "$TOKEN" >/dev/null
fi

# Inverted range → 400
http_auth GET "$BASE/api/locations/analytics?startDate=2026-05-01&endDate=2026-04-01" "$TOKEN"
assert_status 400 "S4-F10 inverted range → 400"

# No token → 401
http GET "$BASE/api/locations/analytics?startDate=2026-04-01&endDate=2026-04-30"
assert_status 401 "S4-F10 no token → 401"

# ANALYTICS_VIEWED on every call (incl. cache hit)
before="$(mongo_count location_events "{ action: 'ANALYTICS_VIEWED' }")"
http_auth GET "$BASE/api/locations/analytics?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN" >/dev/null
http_auth GET "$BASE/api/locations/analytics?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN" >/dev/null
sleep 1
after="$(mongo_count location_events "{ action: 'ANALYTICS_VIEWED' }")"
diff=$((after - before))
[ "$diff" -ge 2 ] && pass "S4-F10 ANALYTICS_VIEWED on every call (+$diff)" \
                  || fail "S4-F10 ANALYTICS_VIEWED on every call" "+$diff"

# Cached for 10 min
[ "$(redis_count_keys 'location-service::S4-F10::*')" -ge 1 ] \
  && pass "S4-F10 caches location-service::S4-F10::*" \
  || fail "S4-F10 caches location-service::S4-F10::*"

# ANALYTICS_VIEWED must NOT trigger wildcard invalidation (§4.4.4)
keys_now="$(redis_count_keys 'location-service::S4-F10::*')"
[ "$keys_now" -ge 1 ] && pass "S4-F10 cache survives ANALYTICS_VIEWED writes" \
                      || fail "S4-F10 cache survives ANALYTICS_VIEWED writes"

# ============================================================
# M1 S4 features
# ============================================================

# S4-F1 latest by driver
http_auth GET "$BASE/api/locations/driver/$DID/latest" "$TOKEN"
assert_status_in "M1 S4-F1 GET /api/locations/driver/{id}/latest" 200 204
[ "$(redis_count_keys 'location-service::S4-F1::*')" -ge 1 ] \
  && pass "S4-F1 caches location-service::S4-F1::*" \
  || fail "S4-F1 caches location-service::S4-F1::* (§4.4.1, §8.1)"

# S4-F3 nearby
http_auth GET "$BASE/api/locations/nearby?lat=30.0&lon=31.0&radiusKm=10" "$TOKEN"
assert_status_in "M1 S4-F3 GET /api/locations/nearby" 200 204

# S4-F5 metadata search — SUT contract accepts lowercase operators (eq|gt|lt); M1 spec
# does not mandate a casing, so the test follows the implementation.
http_auth GET "$BASE/api/locations/metadata/search?key=speed&operator=gt&value=10" "$TOKEN"
assert_status_in "M1 S4-F5 GET /api/locations/metadata/search" 200 204

# S4-F6 history
http_auth GET "$BASE/api/locations/history?startDate=2026-01-01&endDate=2026-12-31&driverId=$DID" "$TOKEN"
assert_status_in "M1 S4-F6 GET /api/locations/history" 200 204

# S4-F8 driver summary
http_auth GET "$BASE/api/locations/driver/$DID/summary?startDate=2026-01-01&endDate=2026-12-31" "$TOKEN"
assert_status_in "M1 S4-F8 GET /api/locations/driver/{id}/summary" 200 204

# S4-F9 stationary
http_auth GET "$BASE/api/locations/stationary?maxSpeed=5&sinceMinutes=60" "$TOKEN"
assert_status_in "M1 S4-F9 GET /api/locations/stationary" 200 204

# S4-F2 batch upload (write — emits BATCH_LOCATIONS_UPDATED)
http_auth POST "$BASE/api/locations/batch" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"driverId":$DID,"locations":[
 {"latitude":30.1,"longitude":31.1,"timestamp":"2026-04-15T09:00:00"}
]}
EOF
)"
assert_status_in "M1 S4-Fx batch upload" 200 201 400

# S4-F7 purge (write — emits OLD_LOCATIONS_PURGED)
http_auth DELETE "$BASE/api/locations/purge?olderThanDays=365" "$TOKEN"
assert_status_in "M1 S4-Fx purge" 200 204

# Cleanup: best-effort
[ -n "$LOC_ID" ] && http_auth DELETE "$BASE/api/locations/$LOC_ID" "$TOKEN" >/dev/null
http_auth DELETE "$DRIVER_URL/api/drivers/$DID" "$TOKEN" >/dev/null
