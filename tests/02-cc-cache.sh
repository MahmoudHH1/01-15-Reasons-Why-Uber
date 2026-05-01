#!/usr/bin/env bash
# CC-3 — Redis caching on all M1 read endpoints + 10 CRUD GET-by-ID
# §4.4   M1 endpoint cache enumeration
# §4.4.5 Cache key convention   <service>::<entity>::<id>   <service>::S<n>-F<m>::<param-hash>
# §4.4.6 Wildcard invalidation
# §8.1   TTL guidelines (entity detail 15m, search 5m, dashboard 10m, activity 5m)
# §8.2   Soft-dep degradation
#
# This script is structured as small, targeted spot checks rather than a
# full sweep of all 27 M1 features (per-service files do the per-feature
# verification). It focuses on the *behaviour*: list-not-cached, get-by-id
# cached, write invalidates, soft-dep graceful degradation.

source "$(dirname "$0")/lib/common.sh"

section "02 CC-3 — Redis cache contract"

TOKEN="$(register_user "cache")"
[ -z "$TOKEN" ] && { fail "register seed user for cache tests" "registration returned empty token"; exit 1; }

# --- (§4.4.6 step c) List endpoints are NOT cached ---------------------

before_list="$(redis_count_keys 'driver-service::driver::*')"
http_auth GET "$DRIVER_URL/api/drivers" "$TOKEN" >/dev/null
http_auth GET "$DRIVER_URL/api/drivers" "$TOKEN" >/dev/null
list_keys="$(redis_keys 'driver-service::driver::list*')"
if [ -z "$list_keys" ]; then
  pass "GET /api/drivers (list) creates NO cache key"
else
  fail "GET /api/drivers (list) creates NO cache key" "found: $list_keys"
fi

# --- 10 CRUD GET-by-ID are cached --------------------------------------
# We only validate one entity per service to keep this script short — full
# coverage lives in the per-service files (10-50).

# Driver — create one and cache it
SALT="${RANDOM}${RANDOM}"
http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Cache Driver $RUN_ID","email":"cd-${SALT}@x.io","phone":"+19${SALT:0:8}",
 "licenseNumber":"LIC-CD-${SALT}",
 "rating":4.0,"totalRatings":0,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"CACHE-${SALT}","description":"black sedan"}}
EOF
)"
DID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$DID" ]; then
  http_auth GET "$DRIVER_URL/api/drivers/$DID" "$TOKEN" >/dev/null
  if [ "$(redis_count_keys "driver-service::driver::$DID")" -ge 1 ]; then
    pass "GET /api/drivers/{id} populates driver-service::driver::$DID"
  else
    fail "GET /api/drivers/{id} populates driver-service::driver::$DID"
  fi

  # Wildcard invalidation on PUT (§4.4.6 step e)
  http_auth PUT "$DRIVER_URL/api/drivers/$DID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Cache Driver Renamed","email":"cd-${SALT}@x.io","phone":"+19${SALT:0:8}",
 "licenseNumber":"LIC-CD-${SALT}",
 "rating":4.5,"totalRatings":1,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"CACHE-${SALT}","description":"black sedan"}}
EOF
)"
  if [ "$(redis_count_keys "driver-service::driver::$DID")" = "0" ]; then
    pass "PUT /api/drivers/{id} invalidates entity-detail key"
  else
    fail "PUT /api/drivers/{id} invalidates entity-detail key" \
         "entity-detail key still present after PUT"
  fi

  # Wildcard feature invalidation: any S2-* key matching this driver must clear
  # (we exercise S2-F1 search; the per-service script covers others)
  http_auth GET "$DRIVER_URL/api/drivers/search?status=AVAILABLE&minRating=0&maxRating=5" "$TOKEN" >/dev/null
  s2f1_before="$(redis_count_keys 'driver-service::S2-F1::*')"
  http_auth PUT "$DRIVER_URL/api/drivers/$DID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Cache Driver Renamed Again","email":"cd-${SALT}@x.io","phone":"+19${SALT:0:8}",
 "licenseNumber":"LIC-CD-${SALT}",
 "rating":4.6,"totalRatings":2,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"CACHE-${SALT}","description":"black sedan"}}
EOF
)"
  s2f1_after="$(redis_count_keys 'driver-service::S2-F1::*')"
  # The pre-warming GET above guarantees ≥1 S2-F1 key exists; the PUT must
  # wildcard-clear them per §4.4.4 + §4.4.6.
  if [ "${s2f1_before:-0}" -ge 1 ] && [ "${s2f1_after:-0}" = "0" ]; then
    pass "Driver write wildcard-invalidates S2-F1::* (§4.4.4 + §4.4.6; $s2f1_before → $s2f1_after)"
  else
    fail "Driver write wildcard-invalidates S2-F1::* (§4.4.4 + §4.4.6)" \
         "expected before≥1 and after=0, got before=$s2f1_before after=$s2f1_after"
  fi

  http_auth DELETE "$DRIVER_URL/api/drivers/$DID" "$TOKEN" >/dev/null
fi

# --- (§4.4.6 step g) Soft-dep graceful degradation -------------------------
# We do NOT actually stop Redis here — that would break the rest of the run.
# We only verify the endpoints behave when cache is empty (which is the
# canonical proxy: a miss path must fall back to PostgreSQL).
redis_flush_pattern 'driver-service::*'
http_auth GET "$DRIVER_URL/api/drivers" "$TOKEN"
assert_status 200 "list /api/drivers still 200 after Redis flush (PG fallback)"

# --- (§8.1) TTL spot check ------------------------------------------------
# We do not wait the full TTL here; instead we read the Redis TTL of an
# entity-detail key and assert it is in the (0, 16*60] window. We rely on
# 15m being the documented entity-detail TTL.
SALT2="${RANDOM}${RANDOM}"
http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"TTL Driver","email":"ttl-${SALT2}@x.io","phone":"+18${SALT2:0:8}",
 "licenseNumber":"LIC-TTL-${SALT2}",
 "rating":3.0,"totalRatings":0,"status":"OFFLINE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SUV","plate":"TTL-${SALT2}","description":""}}
EOF
)"
TID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$TID" ]; then
  http_auth GET "$DRIVER_URL/api/drivers/$TID" "$TOKEN" >/dev/null
  # §8.1 — entity detail views must be cached for 15 minutes (900s).
  # Allow 16 min (960s) upper bound for clock slack; require ttl > 0
  # (i.e. an actual TTL was set, not -1=persist or -2=no-key).
  ttl="$(redis_run TTL "driver-service::driver::$TID" 2>/dev/null | tr -d '\r' | tr -d ' ' | tail -1)"
  if [ "${ttl:-0}" -gt 0 ] 2>/dev/null && [ "${ttl:-0}" -le 960 ]; then
    pass "TTL on driver-service::driver::$TID is ${ttl}s (≤ 16 min, §8.1 entity detail)"
  else
    fail "TTL on driver-service::driver::$TID is ${ttl:-<empty>}s (≤ 16 min, §8.1 entity detail)"
  fi
  http_auth DELETE "$DRIVER_URL/api/drivers/$TID" "$TOKEN" >/dev/null
fi
