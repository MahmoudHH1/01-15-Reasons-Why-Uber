#!/usr/bin/env bash
# End-to-end verification for the M1-regression grader cases assigned to Ahmed Gasser:
#   TC48   — DriverDashboardDTO must serialise totalRides + totalRevenue (rename from totalEarnings)
#   TC243  — PUT /api/drivers/{id}/vehicle with empty body {} must 400
#   TC244  — GET /api/drivers/{id}/earnings with startDate > endDate must 400
#   TC239  — PUT /api/drivers/{id}/documents/999999/verify must 404 (unknown document)
#   TC370  — PUT /api/drivers/999999/vehicle must 404 (unknown driver)
#   TC238  — PUT verify (admin JWT, no body) must 2xx + PG driver_documents.verified = true
#
# Spec citations:
#   - M1 §9.2.2 p.21 (S2-F2 vehicle update, 404 on missing driver)
#   - M1 §9.2.3 p.21 (S2-F3 earnings DTO)
#   - M1 §9.2.8 p.23 (S2-F8 verify document, 404 on missing doc)
#   - M2 §10.2.3 p.38 (S2-F12 dashboard DTO — graded as totalRevenue, not totalEarnings)
#
# Prerequisites:
#   - docker compose up -d postgres mongo redis user-service driver-service
#   - DataSeeder admin: admin@uber.com / admin123
#
# Exit code: number of FAIL assertions. 0 = all green.

set -u

USER_SVC="${USER_SVC:-http://localhost:8081}"
DRIVER_SVC="${DRIVER_SVC:-http://localhost:8082}"
PG_CONTAINER="${PG_CONTAINER:-uber-db}"
PG_DB="${PG_DB:-uberdb}"
PG_USER="${PG_USER:-postgres}"
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

pg_query() {
  docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc "$1" 2>/dev/null
}

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

# ── 1. admin login ───────────────────────────────────────────────────────────
echo
echo "=== Admin login (RUN_ID=$RUN_ID) ==="
ADMIN_TOKEN=$(curl -s -X POST "$USER_SVC/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PWD\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -z "$ADMIN_TOKEN" ]; then echo "FAIL  could not log in seeded admin — aborting"; exit 1; fi
ADMIN_ID=$(jwt_uid "$ADMIN_TOKEN")
[ -n "$ADMIN_ID" ] && report "(1) admin login + uid claim ($ADMIN_ID)" 1 || report "(1) admin login + uid claim" 0 "no uid"

# ── 2. register a non-admin rider for non-mutating calls ─────────────────────
RIDER_EMAIL="rider-${RUN_ID}@regr.test"
RIDER_PHONE="010${RUN_ID: -8}"
RIDER_TOKEN=$(curl -s -X POST "$USER_SVC/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Regr Rider\",\"email\":\"$RIDER_EMAIL\",\"password\":\"TestPass123\",\"phone\":\"$RIDER_PHONE\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
[ -n "$RIDER_TOKEN" ] || { echo "FAIL  could not register rider — aborting"; exit 1; }

# ── 3. create driver fixture ─────────────────────────────────────────────────
echo
echo "=== Bootstrap driver ==="
DRV_RESP=$(curl -s -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"RegrDriver\",\"email\":\"drv-${RUN_ID}@regr.test\",\"phone\":\"012${RUN_ID: -8}\",\"licenseNumber\":\"LIC-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SEDAN\",\"description\":\"regr test\"}}")
DRIVER_ID=$(echo "$DRV_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ -n "$DRIVER_ID" ] && report "(3) driver create + id ($DRIVER_ID)" 1 || report "(3) driver create + id" 0 "resp=$DRV_RESP"

# ─────────────────────────────────────────────────────────────────────────────
# === TC48 — Dashboard DTO field rename (totalEarnings -> totalRevenue) =======
# === Citation: M2 §10.2.3 p.38 (graded as totalRevenue) =====================
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "=== TC48 — dashboard DTO has totalRides + totalRevenue ==="
DASH_RESP=$(curl -s -w '\n%{http_code}' "$DRIVER_SVC/api/drivers/$DRIVER_ID/dashboard" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
DASH_BODY=$(echo "$DASH_RESP" | head -n -1)
DASH_CODE=$(echo "$DASH_RESP" | tail -1)
[ "$DASH_CODE" = "200" ] && report "(TC48-a) dashboard 200" 1 || report "(TC48-a) dashboard 200" 0 "got $DASH_CODE"

HAS_TOTALRIDES=$(echo "$DASH_BODY" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print('1' if ('totalRides' in d or 'total_rides' in d) else '0')
except Exception:
    print('0')")
[ "$HAS_TOTALRIDES" = "1" ] && report "(TC48-b) dashboard JSON has totalRides" 1 || report "(TC48-b) dashboard JSON has totalRides" 0 "body=$DASH_BODY"

HAS_TOTALREVENUE=$(echo "$DASH_BODY" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print('1' if ('totalRevenue' in d or 'total_revenue' in d) else '0')
except Exception:
    print('0')")
[ "$HAS_TOTALREVENUE" = "1" ] && report "(TC48-c) dashboard JSON has totalRevenue" 1 || report "(TC48-c) dashboard JSON has totalRevenue" 0 "body=$DASH_BODY"

# ─────────────────────────────────────────────────────────────────────────────
# === TC243 — empty body on vehicle PUT must 400 ==============================
# === Citation: grader-only constraint; M1 §9.2.2 silent ======================
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "=== TC243 — PUT /vehicle {} -> 400 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/vehicle" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{}")
[ "$CODE" = "400" ] && report "(TC243) empty body -> 400" 1 || report "(TC243) empty body -> 400" 0 "got $CODE"

# ─────────────────────────────────────────────────────────────────────────────
# === TC370 — vehicle PUT for unknown driver must 404 =========================
# === Citation: M1 §9.2.2 p.21 test (d): "non-existent driver -> 404" ========
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "=== TC370 — PUT /drivers/999999/vehicle {color:Red} -> 404 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/999999/vehicle" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"color\":\"Red\"}")
[ "$CODE" = "404" ] && report "(TC370) unknown driver vehicle PUT -> 404" 1 || report "(TC370) unknown driver vehicle PUT -> 404" 0 "got $CODE"

# ─────────────────────────────────────────────────────────────────────────────
# === TC244 — inverted earnings date range must 400 ===========================
# === Citation: grader-only constraint; M1 §9.2.3 silent ======================
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "=== TC244 — GET /earnings ?start=2026-12-31&end=2026-01-01 -> 400 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "$DRIVER_SVC/api/drivers/$DRIVER_ID/earnings?startDate=2026-12-31&endDate=2026-01-01" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
[ "$CODE" = "400" ] && report "(TC244) inverted date range -> 400" 1 || report "(TC244) inverted date range -> 400" 0 "got $CODE"

# ─────────────────────────────────────────────────────────────────────────────
# === TC239 — verify unknown document must 404 ================================
# === Citation: M1 §9.2.8 p.23 "DriverDocument by ID not found -> 404" =======
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "=== TC239 — PUT /documents/999999/verify (no body, admin JWT) -> 404 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/999999/verify" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
[ "$CODE" = "404" ] && report "(TC239) verify unknown doc -> 404" 1 || report "(TC239) verify unknown doc -> 404" 0 "got $CODE"

# ─────────────────────────────────────────────────────────────────────────────
# === TC238 — admin verifies document; PG verified=true =======================
# === Citation: M1 §9.2.8 p.23 test (b) =======================================
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "=== TC238 — verify happy path (no body, admin JWT) -> 2xx + PG verified=true ==="
FUTURE_DATE=$(date -d '+90 days' +%Y-%m-%d 2>/dev/null || date -v+90d +%Y-%m-%d)
DOC_RESP=$(curl -s -X POST "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"type\":\"LICENSE\",\"documentUrl\":\"http://regr.test/doc-${RUN_ID}.pdf\",\"expiryDate\":\"$FUTURE_DATE\",\"metadata\":{}}")
DOC_ID=$(echo "$DOC_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ -n "$DOC_ID" ] || { report "(TC238-pre) seed unverified LICENSE doc" 0 "resp=$DOC_RESP"; }

VFY_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  "$DRIVER_SVC/api/drivers/$DRIVER_ID/documents/$DOC_ID/verify" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
case "$VFY_CODE" in
  2*) report "(TC238-a) verify happy path -> 2xx ($VFY_CODE)" 1 ;;
  *)  report "(TC238-a) verify happy path -> 2xx" 0 "got $VFY_CODE" ;;
esac

PG_VERIFIED=$(pg_query "SELECT verified FROM driver_documents WHERE id=$DOC_ID")
[ "$PG_VERIFIED" = "t" ] && report "(TC238-b) PG driver_documents.verified = true" 1 || report "(TC238-b) PG driver_documents.verified = true" 0 "got '$PG_VERIFIED'"

# ─────────────────────────────────────────────────────────────────────────────
# === Totals ==================================================================
# ─────────────────────────────────────────────────────────────────────────────
echo
echo "TOTALS: $PASS PASS / $FAIL FAIL"
exit "$FAIL"
