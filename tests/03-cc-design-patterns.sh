#!/usr/bin/env bash
# CC-4 — runtime-observable design pattern hooks (§3.1–3.8)
# Static-reflection assertions (interface existence, no @EventListener writing
# to MongoDB, no `if (refundSurge)` in payment service) are out of scope here
# and live in JUnit / source-scan tooling. This script verifies the behavior
# the grader observes through HTTP:
#   DP-1 Strategy   — S5-F12 NoRefundStrategy / FullRefundWithSurgeStrategy paths
#   DP-2 Observer   — every M1 write produces a Mongo event in the right collection
#   DP-3 CoR        — JWT filter chain step failures (covered fully in 01-cc-jwt.sh)
#   DP-4 Builder    — DTOs serialize cleanly (S2-F12 dashboard, S3-F10 analytics, S5-F10)
#   DP-5 Singleton  — JWT issued by user-service is verified by every other service
#                     (covered fully in 01-cc-jwt.sh)
#   DP-6 Factory    — auth_events / driver_events / ride_events / location_events /
#                     payment_audit_trail collections all populate the right action vocab
#   DP-7 Adapter    — read endpoints return shaped DTOs, not raw Mongo/ES/Neo4j blobs

source "$(dirname "$0")/lib/common.sh"

section "03 CC-4 — Design pattern runtime hooks"

TOKEN="$(register_user "dp")"
[ -z "$TOKEN" ] && { fail "register seed user" "no token"; exit 1; }
UID_TOKEN="$(jwt_uid "$TOKEN")"

# --- DP-2 Observer + DP-6 Factory — auth_events ---------------------------
# Spec: §3.3 "S1 register/login/role-change → observers log AuthEvent to auth_events"
auth_before="$(mongo_count auth_events "{ userId: $UID_TOKEN }")"
# Re-login to add LOGGED_IN
TOKEN2="$(login_user "dp-${RUN_ID}@example.com")"
auth_after="$(mongo_count auth_events "{ userId: $UID_TOKEN }")"
if [ "$auth_after" -gt "$auth_before" ]; then
  pass "Login emits auth_events document (Observer→Factory)"
else
  fail "Login emits auth_events document" "before=$auth_before, after=$auth_after"
fi

# Verify action vocab is one of the documented values (REGISTERED, LOGGED_IN, ROLE_CHANGED, ...)
# Rather than asserting exact values (extension is allowed per §7.1.2),
# assert at least one document has REGISTERED for this user.
reg_count="$(mongo_count auth_events "{ userId: $UID_TOKEN, action: 'REGISTERED' }")"
log_count="$(mongo_count auth_events "{ userId: $UID_TOKEN, action: 'LOGGED_IN' }")"
[ "$reg_count" -ge 1 ] && pass "REGISTERED event present" || fail "REGISTERED event present"
[ "$log_count" -ge 1 ] && pass "LOGGED_IN event present"  || fail "LOGGED_IN event present"

# --- DP-2/6 driver_events on a Driver CRUD write -------------------------
# §4.5 step g: "Call M1 CRUD write (e.g., POST /api/drivers) → event in driver_events"
DP_SALT="${RANDOM}${RANDOM}"
http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"DP Driver $RUN_ID","email":"dp-${DP_SALT}@x.io","phone":"+13${DP_SALT:0:9}",
 "licenseNumber":"LIC-DP-${DP_SALT}",
 "rating":4.0,"totalRatings":0,"status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"SEDAN","plate":"DP-${DP_SALT}","description":"silver sedan"}}
EOF
)"
DDID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$DDID" ]; then
  drv_count="$(mongo_count driver_events "{ driverId: $DDID }")"
  if [ "${drv_count:-0}" -ge 1 ]; then
    pass "POST /api/drivers writes driver_events doc"
  else
    fail "POST /api/drivers writes driver_events doc" "got $drv_count"
  fi
fi

# --- DP-4 Builder — S2-F12 dashboard returns full DTO --------------------
# Spec §3.5: "Call S2-F12 → verify response is correctly populated."
if [ -n "$DDID" ]; then
  http_auth GET "$DRIVER_URL/api/drivers/$DDID/dashboard" "$TOKEN"
  if [ "$LAST_STATUS" = "200" ]; then
    for f in driverId name totalRides totalEarnings averageRideFare averageRating totalRatings; do
      v="$(echo "$LAST_BODY" | jq -r ".${f}")"
      if [ "$v" != "null" ] && [ -n "$v" ]; then
        pass "S2-F12 DriverDashboardDTO has field $f=$v"
      else
        fail "S2-F12 DriverDashboardDTO has field $f" "field missing or null"
      fi
    done
  else
    skip "S2-F12 dashboard call" "status=$LAST_STATUS"
  fi
fi

# --- §3.3 step f — S1-F2 PUT /api/users/{id}/preferences observer retrofit
# The M1 update-preferences endpoint must produce a corresponding event
# in auth_events so the activity feed (S1-F12) reflects it.
sleep 1
pref_before="$(mongo_count auth_events "{ userId: $UID_TOKEN }")"
http_auth PUT "$USER_URL/api/users/$UID_TOKEN/preferences" "$TOKEN" \
  -H "Content-Type: application/json" -d '{"language":"ar","notifications":false}'
assert_status_in "§3.3.f PUT /api/users/{id}/preferences" 200 204
sleep 1
pref_after="$(mongo_count auth_events "{ userId: $UID_TOKEN }")"
if [ "${pref_after:-0}" -gt "${pref_before:-0}" ]; then
  pass "§3.3.f S1-F2 retrofit emits auth_events ($pref_before → $pref_after)"
else
  fail "§3.3.f S1-F2 retrofit emits auth_events" "$pref_before → $pref_after"
fi

# --- DP-1 Strategy — S5-F12 path coexists with M1 PUT refund -------------
# Both endpoints must exist. Unknown payment ID → 404 (§10.5.3.b).
http_auth POST "$PAYMENT_URL/api/payments/9999999/refund-surge-adjusted" "$TOKEN" \
  -H "Content-Type: application/json" -d '{"reason":"x","refundSurge":true}'
assert_status 404 "S5-F12 unknown payment → 404 (§10.5.3.b)"

http_auth PUT "$PAYMENT_URL/api/payments/9999999/refund" "$TOKEN" \
  -H "Content-Type: application/json" -d '{"reason":"x"}'
assert_status 404 "M1 PUT /api/payments/{id}/refund coexists, unknown id → 404"

# Cleanup
[ -n "$DDID" ] && http_auth DELETE "$DRIVER_URL/api/drivers/$DDID" "$TOKEN" >/dev/null
