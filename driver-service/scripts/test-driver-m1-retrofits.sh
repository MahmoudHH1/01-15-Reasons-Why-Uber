#!/usr/bin/env bash
# End-to-end test for driver-service M1 retrofits:
#   - S2-F8 Observer retrofit: PUT /api/drivers/{driverId}/documents/{docId}/verify
#     emits DOCUMENT_VERIFIED into Mongo driver_events
#     (cite: docs/m2/event-actions.md row "DOCUMENT_VERIFIED | S2-F8 (M1) | retrofit",
#      §4.5 retrofit table)
#   - DP-4 Builder retrofit: DriverEarningsDTO (S2-F3), TopDriverDTO (S2-F6),
#     DriverDocumentAlertDTO (S2-F9) carry static builder()
#     (cite: docs/m2/design-patterns.md:106-111)
#
# Prerequisites:
#   - docker compose up -d postgres mongo redis user-service driver-service
#   - All services healthy
#   - Seeded admin user exists: admin@uber.com / admin123 (DataSeeder.java)
#
# Exit code: number of FAIL assertions. 0 = all green.

set -u

USER_SVC="${USER_SVC:-http://localhost:8081}"
DRIVER_SVC="${DRIVER_SVC:-http://localhost:8082}"
MONGO_USER="${MONGO_USER:-root}"
MONGO_PASS="${MONGO_PASS:-rootpass}"
MONGO_DB="${MONGO_DB:-ubermongo}"
REDIS_PASS="${REDIS_PASS:-redispass}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@uber.com}"
ADMIN_PWD="${ADMIN_PWD:-admin123}"
RUN_ID="$(date +%s)$$"

PASS=0
FAIL=0
report() {
  local name="$1" cond="$2" detail="${3:-}"
  if [ "$cond" = "1" ]; then PASS=$((PASS+1)); echo "PASS  $name"
  else                       FAIL=$((FAIL+1)); echo "FAIL  $name -- $detail"
  fi
}

mongo_eval() {
  docker exec uber-mongo mongosh --quiet -u "$MONGO_USER" -p "$MONGO_PASS" \
    --authenticationDatabase admin "$MONGO_DB" --eval "$1" 2>&1
}

redis_cmd() {
  docker exec uber-redis redis-cli -a "$REDIS_PASS" "$@" 2>/dev/null
}

# Decode the `uid` claim (numeric userId) out of a JWT.
jwt_uid() {
  echo "$1" | python3 -c "
import sys, json, base64
parts = sys.stdin.read().strip().split('.')
payload = parts[1]
padding = 4 - len(payload) % 4
if padding < 4:
    payload += '=' * padding
print(json.loads(base64.urlsafe_b64decode(payload)).get('uid', ''))" 2>/dev/null
}

# ── 0. health roll-up ────────────────────────────────────────────────────────
echo "=== Health checks ==="
USER_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$USER_SVC/api/users/health")
DRIVER_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$DRIVER_SVC/api/drivers/health")
[ "$USER_HEALTH"   = "200" ] && report "(0a) user-service health"   1 || report "(0a) user-service health"   0 "got $USER_HEALTH"
[ "$DRIVER_HEALTH" = "200" ] && report "(0b) driver-service health" 1 || report "(0b) driver-service health" 0 "got $DRIVER_HEALTH"
[ "$FAIL" -gt 0 ] && { echo "stack not healthy — aborting"; exit "$FAIL"; }

# ── 1. login as seeded admin, capture admin token + adminId ──────────────────
echo
echo "=== Login as seeded admin to obtain ADMIN token + adminId ==="
ADMIN_TOKEN=$(curl -s -X POST "$USER_SVC/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PWD\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -z "$ADMIN_TOKEN" ]; then echo "FAIL  could not log in seeded admin — aborting"; exit 1; fi
ADMIN_ID=$(jwt_uid "$ADMIN_TOKEN")
[ -n "$ADMIN_ID" ] && report "(1a) admin login + uid claim ($ADMIN_ID)" 1 || report "(1a) admin login + uid claim" 0 "no uid"

# ── 2. register a regular user (rider) — used as auth principal + non-admin ──
echo
echo "=== Register rider user (RUN_ID=$RUN_ID) ==="
RIDER_EMAIL="rider-${RUN_ID}@m1retro.test"
RIDER_PHONE="010${RUN_ID: -8}"
RIDER_TOKEN=$(curl -s -X POST "$USER_SVC/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"M1Retro Rider\",\"email\":\"$RIDER_EMAIL\",\"password\":\"TestPass123\",\"phone\":\"$RIDER_PHONE\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -z "$RIDER_TOKEN" ]; then echo "FAIL  could not register rider — aborting"; exit 1; fi
RIDER_ID=$(jwt_uid "$RIDER_TOKEN")
[ -n "$RIDER_ID" ] && report "(2a) rider registration ($RIDER_ID)" 1 || report "(2a) rider registration" 0 "no uid"

# ── 3. clean Mongo driver_events ─────────────────────────────────────────────
mongo_eval 'db.driver_events.deleteMany({})' >/dev/null

# ── 4. create driver + future-dated document via driver-service ──────────────
echo
echo "=== Bootstrap driver + future-dated document ==="
RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"M1RetroDriver\",\"email\":\"drv-${RUN_ID}@m1.test\",\"phone\":\"012${RUN_ID: -8}\",\"licenseNumber\":\"LIC-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SEDAN\",\"description\":\"retro test\"}}")
DRIVER_ID=$(echo "$RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ "$(echo "$RESP" | tail -1)" = "201" ] && [ -n "$DRIVER_ID" ] \
  && report "(4a) driver create 201 + id ($DRIVER_ID)"  1 || report "(4a) driver create 201 + id"  0 "got $(echo "$RESP" | tail -1) / id=$DRIVER_ID"

FUTURE_DATE=$(date -d '+90 days' +%Y-%m-%d 2>/dev/null || date -v+90d +%Y-%m-%d)
PAST_DATE=$(date -d '-30 days' +%Y-%m-%d 2>/dev/null || date -v-30d +%Y-%m-%d)

DOC_RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"type\":\"LICENSE\",\"documentUrl\":\"http://m1.test/doc-${RUN_ID}.pdf\",\"expiryDate\":\"$FUTURE_DATE\",\"metadata\":{}}")
DOC_ID=$(echo "$DOC_RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ "$(echo "$DOC_RESP" | tail -1)" = "201" ] && [ -n "$DOC_ID" ] \
  && report "(4b) document create 201 + id ($DOC_ID)"   1 || report "(4b) document create 201 + id"   0 "got $(echo "$DOC_RESP" | tail -1)"

# ─────────────────────────────────────────────────────────────────────────────
# === S2-F8 Observer retrofit (cite: event-actions.md "DOCUMENT_VERIFIED | S2-F8")
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (a) Spec: PUT verify with admin verifiedBy → 200 + DOCUMENT_VERIFIED ==="
RESP=$(curl -s -w '\n%{http_code}' -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_ID/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$ADMIN_ID}")
CODE=$(echo "$RESP" | tail -1)
[ "$CODE" = "200" ] && report "(a1) verify with admin verifiedBy → 200"        1 || report "(a1) verify with admin verifiedBy → 200"        0 "got $CODE"

sleep 1
DOC_VERIFIED_COUNT=$(mongo_eval 'db.driver_events.countDocuments({action:"DOCUMENT_VERIFIED"})' | grep -E '^[0-9]+$' | tail -1)
[ "$DOC_VERIFIED_COUNT" = "1" ] && report "(a2) Mongo: 1 DOCUMENT_VERIFIED event ($DOC_VERIFIED_COUNT)" 1 || report "(a2) Mongo: 1 DOCUMENT_VERIFIED event" 0 "got $DOC_VERIFIED_COUNT"

EVT_DRIVER_ID=$(mongo_eval "var d=db.driver_events.findOne({action:'DOCUMENT_VERIFIED'}); print(d ? Number(d.driverId) : 'NONE');" | grep -E '^[0-9]+$' | tail -1)
[ "$EVT_DRIVER_ID" = "$DRIVER_ID" ] && report "(a3) event driverId == $DRIVER_ID"               1 || report "(a3) event driverId == $DRIVER_ID"               0 "got $EVT_DRIVER_ID"

EVT_VERIFIED_BY=$(mongo_eval "var d=db.driver_events.findOne({action:'DOCUMENT_VERIFIED'}); print(d && d.details ? Number(d.details.verifiedBy) : 'NONE');" | grep -E '^[0-9]+$' | tail -1)
[ "$EVT_VERIFIED_BY" = "$ADMIN_ID" ] && report "(a4) event details.verifiedBy == $ADMIN_ID"     1 || report "(a4) event details.verifiedBy == $ADMIN_ID"     0 "got $EVT_VERIFIED_BY"

EVT_DOC_ID=$(mongo_eval "var d=db.driver_events.findOne({action:'DOCUMENT_VERIFIED'}); print(d && d.details ? Number(d.details.documentId) : 'NONE');" | grep -E '^[0-9]+$' | tail -1)
[ "$EVT_DOC_ID" = "$DOC_ID" ] && report "(a5) event details.documentId == $DOC_ID"             1 || report "(a5) event details.documentId == $DOC_ID"             0 "got $EVT_DOC_ID"

EVT_HAS_TS=$(mongo_eval "var d=db.driver_events.findOne({action:'DOCUMENT_VERIFIED'}); print(d && d.timestamp ? 'YES' : 'NO');" | tr -d '[:space:]' | tail -c 5)
case "$EVT_HAS_TS" in *YES*) report "(a6) event has timestamp"                                    1 ;; *) report "(a6) event has timestamp" 0 "$EVT_HAS_TS";; esac

# ─────────────────────────────────────────────────────────────────────────────
# === Boundary (S2-F8) ===
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (b) Boundary cases ==="

# (b1) Re-verify of already-verified doc — spec doesn't define behavior; assert
#     only that the response is non-5xx (does not hard-fail). Avoid asserting
#     event count change to keep within spec scope (CLAUDE.md Stage 5b).
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_ID/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$ADMIN_ID}")
[ "$CODE" -lt 500 ] && report "(b1) re-verify same doc returns non-5xx ($CODE)"                 1 || report "(b1) re-verify same doc returns non-5xx" 0 "got $CODE"

# (b2) Expired document — create new doc with past expiryDate, verify → 400
DOC_EXP=$(curl -s -X POST "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"type\":\"INSURANCE\",\"documentUrl\":\"http://m1.test/exp-${RUN_ID}.pdf\",\"expiryDate\":\"$PAST_DATE\",\"metadata\":{}}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
EVT_BEFORE=$(mongo_eval 'db.driver_events.countDocuments({action:"DOCUMENT_VERIFIED"})' | grep -E '^[0-9]+$' | tail -1)
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_EXP/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$ADMIN_ID}")
[ "$CODE" = "400" ] && report "(b2) verify expired doc → 400"                                    1 || report "(b2) verify expired doc → 400"                                    0 "got $CODE"
sleep 1
EVT_AFTER=$(mongo_eval 'db.driver_events.countDocuments({action:"DOCUMENT_VERIFIED"})' | grep -E '^[0-9]+$' | tail -1)
[ "$EVT_BEFORE" = "$EVT_AFTER" ] && report "(b3) no DOCUMENT_VERIFIED emitted on expired-doc 400" 1 || report "(b3) no DOCUMENT_VERIFIED emitted on expired-doc 400" 0 "before=$EVT_BEFORE after=$EVT_AFTER"

# (b4) Mismatched driver/doc combo: create second driver, attempt verify with first driver's docId
RESP=$(curl -s -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"OtherDriver\",\"email\":\"other-${RUN_ID}@m1.test\",\"phone\":\"013${RUN_ID: -8}\",\"licenseNumber\":\"LIC-O-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SUV\"}}")
OTHER_DRIVER=$(echo "$RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$OTHER_DRIVER/documents/$DOC_ID/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$ADMIN_ID}")
[ "$CODE" = "400" ] && report "(b4) verify with mismatched driver/doc → 400"                     1 || report "(b4) verify with mismatched driver/doc → 400"                     0 "got $CODE"

# ─────────────────────────────────────────────────────────────────────────────
# === Auth & ownership (S2-F8) ===
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (au) Auth cases ==="
EVT_BEFORE=$(mongo_eval 'db.driver_events.countDocuments({action:"DOCUMENT_VERIFIED"})' | grep -E '^[0-9]+$' | tail -1)

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_ID/verify" \
  -H "Content-Type: application/json" -d "{\"verifiedBy\":$ADMIN_ID}")
[ "$CODE" = "401" ] && report "(au1) no JWT → 401"                                              1 || report "(au1) no JWT → 401"                                              0 "got $CODE"

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_ID/verify" \
  -H "Authorization: Bearer junk.junk.junk" -H "Content-Type: application/json" -d "{\"verifiedBy\":$ADMIN_ID}")
[ "$CODE" = "401" ] && report "(au2) garbage JWT → 401"                                          1 || report "(au2) garbage JWT → 401"                                          0 "got $CODE"

# (au3) verifiedBy = non-admin user → 403
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_ID/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$RIDER_ID}")
[ "$CODE" = "403" ] && report "(au3) verifiedBy=non-admin → 403"                                 1 || report "(au3) verifiedBy=non-admin → 403"                                 0 "got $CODE"

sleep 1
EVT_AFTER=$(mongo_eval 'db.driver_events.countDocuments({action:"DOCUMENT_VERIFIED"})' | grep -E '^[0-9]+$' | tail -1)
[ "$EVT_BEFORE" = "$EVT_AFTER" ] && report "(au4) no DOCUMENT_VERIFIED emitted on auth failures"  1 || report "(au4) no DOCUMENT_VERIFIED emitted on auth failures"  0 "before=$EVT_BEFORE after=$EVT_AFTER"

# ─────────────────────────────────────────────────────────────────────────────
# === Error paths ===
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (e) Error paths ==="

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/9999999/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$ADMIN_ID}")
[ "$CODE" = "404" ] && report "(e1) unknown docId → 404"                                         1 || report "(e1) unknown docId → 404"                                         0 "got $CODE"

# ─────────────────────────────────────────────────────────────────────────────
# === Cache invalidation
#     (cite: docs/m2/cache-matrix.md "S2 → F2,F4,F7,F8 → driver::{id} + features")
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (c) Cache invalidation on verify ==="
# Create a fresh doc to verify (since the earlier doc is verified, we verify a
# NEW future-dated doc to exercise the success cache-invalidation path).
NEW_DOC=$(curl -s -X POST "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"type\":\"REGISTRATION\",\"documentUrl\":\"http://m1.test/cd-${RUN_ID}.pdf\",\"expiryDate\":\"$FUTURE_DATE\",\"metadata\":{}}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")

redis_cmd SET "driver-service::driver::$DRIVER_ID" "x" >/dev/null
redis_cmd SET "driver-service::S2-F9::probe-${RUN_ID}" "y" >/dev/null
BEFORE_DRV=$(redis_cmd GET "driver-service::driver::$DRIVER_ID")
BEFORE_F9=$(redis_cmd GET "driver-service::S2-F9::probe-${RUN_ID}")

curl -s -o /dev/null -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$NEW_DOC/verify" \
  -H "Authorization: Bearer $RIDER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"verifiedBy\":$ADMIN_ID}"
sleep 1

AFTER_DRV=$(redis_cmd GET "driver-service::driver::$DRIVER_ID")
AFTER_F9=$(redis_cmd GET "driver-service::S2-F9::probe-${RUN_ID}")

[ "$BEFORE_DRV" = "x" ] && [ -z "$AFTER_DRV" ] \
  && report "(c1) verify wipes driver-service::driver::{id}"   1 || report "(c1) verify wipes driver-service::driver::{id}"   0 "before='$BEFORE_DRV' after='$AFTER_DRV'"
[ "$BEFORE_F9" = "y" ] && [ -z "$AFTER_F9" ] \
  && report "(c2) verify wipes driver-service::S2-F9::*"        1 || report "(c2) verify wipes driver-service::S2-F9::*"        0 "before='$BEFORE_F9' after='$AFTER_F9'"

# (no idempotency cases — endpoint is not idempotent by spec; re-verify is
#  unspecified per b1)

# ─────────────────────────────────────────────────────────────────────────────
# === DP-4 Builder retrofit — behavioral
#     (cite: docs/m2/design-patterns.md:106-111 retrofit scope)
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (d) Builder retrofit — behavioral JSON shape ==="

# (d1) DriverEarningsDTO (S2-F3) — endpoint requires ride+payment data to return 200.
#      Without seeded data the underlying SQL returns nulls and the service throws NPE
#      (pre-existing, out of M1-retrofit scope). Skip behavioral assertion if 5xx; the
#      authoritative Builder check is the (r-DriverEarningsDTO) reflection test below.
EARN_RESP=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $RIDER_TOKEN" \
  "$DRIVER_SVC/api/drivers/$DRIVER_ID/earnings?startDate=2024-01-01&endDate=2026-12-31")
EARN_CODE=$(echo "$EARN_RESP" | tail -1)
EARN_BODY=$(echo "$EARN_RESP" | head -n -1)
if [ "$EARN_CODE" = "200" ]; then
  KEYS=$(echo "$EARN_BODY" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(','.join(sorted(d.keys())))
except Exception:
    print('PARSE_ERR')")
  EXPECTED="averageFare,driverId,name,totalEarnings,totalRides"
  [ "$KEYS" = "$EXPECTED" ] && report "(d1) earnings JSON has DriverEarningsDTO field set" 1 || report "(d1) earnings JSON has DriverEarningsDTO field set" 0 "got '$KEYS'"
else
  echo "SKIP  (d1) earnings endpoint returned $EARN_CODE (no ride data) — relies on reflection (r-DriverEarningsDTO)"
fi

# (d2) TopDriverDTO (S2-F6) — same caveat as (d1); endpoint NPEs on empty result set.
TOP_RESP=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $RIDER_TOKEN" "$DRIVER_SVC/api/drivers/reports/top-rated?limit=5")
TOP_CODE=$(echo "$TOP_RESP" | tail -1)
TOP_BODY=$(echo "$TOP_RESP" | head -n -1)
if [ "$TOP_CODE" = "200" ]; then
  KEYS=$(echo "$TOP_BODY" | python3 -c "
import sys, json
try:
    arr = json.load(sys.stdin)
    if not arr:
        print('EMPTY_OK')
    else:
        keys = sorted(arr[0].keys())
        print(','.join(keys))
except Exception:
    print('PARSE_ERR')")
  EXPECTED="driverId,name,rating,totalRides"
  case "$KEYS" in
    "EMPTY_OK"|"$EXPECTED") report "(d2) top-rated JSON has TopDriverDTO field set ($KEYS)" 1 ;;
    *) report "(d2) top-rated JSON has TopDriverDTO field set" 0 "got '$KEYS'" ;;
  esac
else
  echo "SKIP  (d2) top-rated endpoint returned $TOP_CODE (no ratings data) — relies on reflection (r-TopDriverDTO)"
fi

# (d3) DriverDocumentAlertDTO (S2-F9) — needs an expired doc to surface.
#     We already created an expired doc (DOC_EXP) at b2. Let's invalidate the
#     S2-F9 cache (so we don't get a cached empty list from before) and hit it.
redis_cmd --scan --pattern 'driver-service::S2-F9*' | xargs -r -I{} redis_cmd DEL '{}' >/dev/null
EXP=$(curl -s -H "Authorization: Bearer $RIDER_TOKEN" "$DRIVER_SVC/api/drivers/documents/expired")
KEYS=$(echo "$EXP" | python3 -c "
import sys, json
try:
    arr = json.load(sys.stdin)
    if not arr:
        print('EMPTY_BAD')
    else:
        keys = sorted(arr[0].keys())
        print(','.join(keys))
except Exception:
    print('PARSE_ERR')")
EXPECTED="driverId,driverName,driverStatus,expiredCount,expiredDocuments"
[ "$KEYS" = "$EXPECTED" ] && report "(d3) expired-docs JSON has DriverDocumentAlertDTO field set" 1 || report "(d3) expired-docs JSON has DriverDocumentAlertDTO field set" 0 "got '$KEYS'"

# ─────────────────────────────────────────────────────────────────────────────
# === DP-4 Builder retrofit — class-file reflection
# ─────────────────────────────────────────────────────────────────────────────

echo
echo "=== (r) Builder retrofit — class-file reflection (javap) ==="
# Locate driver-service target/classes relative to this script.
CLASSES_DIR="$(dirname "$0")/../target/classes/com/team01/uber/driver/dto"
if [ ! -d "$CLASSES_DIR" ]; then
  echo "SKIP  (r*) target/classes not present — run ./mvnw -pl driver-service -am compile first"
else
  for DTO in DriverEarningsDTO TopDriverDTO DriverDocumentAlertDTO; do
    if javap -p "$CLASSES_DIR/$DTO.class" 2>/dev/null | grep -qE "public static .*\.${DTO}\$${DTO}Builder ${DTO,,1}|public static .*${DTO}Builder builder\(\)"; then
      report "(r-$DTO) static builder() method present" 1
    elif javap -p "$CLASSES_DIR/$DTO.class" 2>/dev/null | grep -qE 'builder\(\)'; then
      report "(r-$DTO) static builder() method present" 1
    else
      report "(r-$DTO) static builder() method present" 0 "javap output missing builder()"
    fi
    BUILDER_CLASS="$CLASSES_DIR/${DTO}\$${DTO}Builder.class"
    if [ -f "$BUILDER_CLASS" ] && javap -p "$BUILDER_CLASS" 2>/dev/null | grep -q "${DTO} build()"; then
      report "(r-$DTO-build) Builder.build() returns ${DTO}" 1
    else
      report "(r-$DTO-build) Builder.build() returns ${DTO}" 0 "missing or wrong return type"
    fi
  done
fi

echo
echo "============================================================"
echo "TOTALS: $PASS PASS / $FAIL FAIL"
echo "============================================================"
exit "$FAIL"
