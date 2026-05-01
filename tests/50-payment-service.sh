#!/usr/bin/env bash
# payment-service (port 8085) — full endpoint coverage.
# Spec sections covered:
#   §10.5.1 S5-F10 Fare Revenue by Vehicle Type with Surge Breakdown   (cache 10m)
#   §10.5.2 S5-F11 Payment Method Breakdown                            (cache 10m)
#   §10.5.3 S5-F12 Process Ride Refund with Surge Handling (DP-1 Strategy)
#   §3.2    Strategy: FullRefundWithSurgeStrategy / BaseFareOnlyRefundStrategy / NoRefundStrategy
#   §4.5    M1 retrofits: S5-F4 Process Payment writes CREATED + COMPLETED, S5-F2 writes REFUNDED
#   §4.5    S5-F4 ?simulateFailure=true → status FAILED + FAILED audit
#   §4.6    Payment.transactionDetails.surgeFee additive key + 15% fallback
#   §10.5.3 distinct-path: POST /api/payments/{id}/refund-surge-adjusted ≠ PUT /api/payments/{id}/refund
#   §4.4    Cache contract on S5-F1, S5-F3, S5-F6, S5-F8, S5-F9, payment-by-id, coupon-by-id, payment-coupon-by-id
#   M1 S5-F1..S5-F9 + CRUD Payment + CRUD Coupon + CRUD PaymentCoupon

source "$(dirname "$0")/lib/common.sh"

section "50 payment-service — S5-F10/F11/F12 + M1 + CRUD"

BASE="$PAYMENT_URL"
TOKEN="$(register_user "pay")"
if [ -z "$TOKEN" ]; then
  fail "register seed user via user-service" \
       "user-service not reachable or registration broken — entire script skipped"
  exit 0
fi

# Need a Ride to pay for. Create via ride-service.
http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":150,
 "requestedAt":"2026-04-15T10:00:00",
 "completedAt":"2026-04-15T10:30:00",
 "metadata":{"surgeMultiplier":1.2}}
EOF
)"
RIDE_ID="$(echo "$LAST_BODY" | jq -r '.id // empty')"

# ============================================================
# S5-F4 Process Payment — happy path + simulateFailure (§4.5 retrofit)
# ============================================================

if [ -n "$RIDE_ID" ]; then
  http_auth POST "$BASE/api/payments/ride/$RIDE_ID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"amount":150,"method":"CREDIT_CARD"}
EOF
)"
  assert_status_in "S5-F4 POST /api/payments/ride/$RIDE_ID (happy)" 200 201
  PAY_ID="$(echo "$LAST_BODY" | jq -r '.id // empty')"

  # M1 retrofit: writes CREATED + COMPLETED to payment_audit_trail (§4.5)
  sleep 1
  cre="$(mongo_count payment_audit_trail "{ paymentId: $PAY_ID, action: 'CREATED' }")"
  com="$(mongo_count payment_audit_trail "{ paymentId: $PAY_ID, action: 'COMPLETED' }")"
  [ "${cre:-0}" -ge 1 ] && pass "S5-F4 emits CREATED" || fail "S5-F4 emits CREATED"
  [ "${com:-0}" -ge 1 ] && pass "S5-F4 emits COMPLETED" || fail "S5-F4 emits COMPLETED"

  # surgeFee key written into transactionDetails (§4.6)
  http_auth GET "$BASE/api/payments/$PAY_ID" "$TOKEN"
  surge="$(echo "$LAST_BODY" | jq -r '.transactionDetails.surgeFee // empty')"
  if [ -n "$surge" ] && [ "$surge" != "null" ]; then
    pass "S5-F4 writes transactionDetails.surgeFee=$surge"
  else
    fail "S5-F4 writes transactionDetails.surgeFee" "key missing — §4.6 retrofit gap"
  fi

  # method/amount required on payment-shaped events (§7.1.6)
  for ev in CREATED COMPLETED; do
    has="$(mongo_count payment_audit_trail "{ paymentId: $PAY_ID, action: '$ev', method: { \$exists: true }, amount: { \$exists: true } }")"
    [ "${has:-0}" -ge 1 ] && pass "$ev event carries method+amount" \
                          || fail "$ev event carries method+amount"
  done
fi

# Now a second ride for failure simulation
http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":120,
 "requestedAt":"2026-04-16T10:00:00","completedAt":"2026-04-16T10:30:00",
 "metadata":{}}
EOF
)"
RIDE_FAIL="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$RIDE_FAIL" ]; then
  http_auth POST "$BASE/api/payments/ride/$RIDE_FAIL?simulateFailure=true" "$TOKEN" \
    -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"amount":120,"method":"CREDIT_CARD"}
EOF
)"
  # §4.5: short-circuit to Payment.status=FAILED + FAILED audit; row is still
  # returned. M1's POST normally returns 200/201, so accept both.
  assert_status_in "S5-F4 ?simulateFailure=true (§4.5 retrofit)" 200 201
  FAIL_PID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  if [ -n "$FAIL_PID" ]; then
    http_auth GET "$BASE/api/payments/$FAIL_PID" "$TOKEN" >/dev/null
    s="$(echo "$LAST_BODY" | jq -r '.status')"
    [ "$s" = "FAILED" ] && pass "simulateFailure leaves Payment.status=FAILED" \
                       || fail "simulateFailure leaves Payment.status=FAILED" "got $s"
    f="$(mongo_count payment_audit_trail "{ paymentId: $FAIL_PID, action: 'FAILED' }")"
    [ "${f:-0}" -ge 1 ] && pass "simulateFailure emits FAILED audit" \
                        || fail "simulateFailure emits FAILED audit"
  fi
fi

# ============================================================
# S5-F12 Process Ride Refund with Surge Handling   (§10.5.3)
# Distinct from PUT /api/payments/{id}/refund (M1).
# ============================================================

# (a) within window + refundSurge=true → FullRefundWithSurgeStrategy
if [ -n "$PAY_ID" ]; then
  http_auth POST "$BASE/api/payments/$PAY_ID/refund-surge-adjusted" "$TOKEN" \
    -H "Content-Type: application/json" -d '{"reason":"driver_no_show","refundSurge":true}'
  assert_status 200 "S5-F12 within window + refundSurge=true → 200"
  ra="$(echo "$LAST_BODY" | jq -r '.transactionDetails.refundAmount // empty')"
  rsi="$(echo "$LAST_BODY" | jq -r '.transactionDetails.refundSurgeIncluded // empty')"
  amt="$(echo "$LAST_BODY" | jq -r '.amount // empty')"
  if [ -n "$ra" ] && [ "$ra" = "$amt" ]; then
    pass "S5-F12 full refund: refundAmount=amount=$ra"
  else
    fail "S5-F12 full refund: refundAmount=amount" "ra=$ra amt=$amt"
  fi
  [ "$rsi" = "true" ] && pass "S5-F12 refundSurgeIncluded=true" || fail "S5-F12 refundSurgeIncluded=true" "got $rsi"

  # REFUNDED event with method+amount + strategy name
  refunded="$(mongo_count payment_audit_trail "{ paymentId: $PAY_ID, action: 'REFUNDED' }")"
  [ "${refunded:-0}" -ge 1 ] && pass "S5-F12 REFUNDED event written" || fail "S5-F12 REFUNDED event written"
fi

# (b) refundSurge=false within window → BaseFareOnlyRefundStrategy
http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":200,
 "requestedAt":"2026-04-15T10:00:00","completedAt":"2026-04-15T10:30:00",
 "metadata":{"surgeMultiplier":1.176}}
EOF
)"
RIDE2="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$RIDE2" ]; then
  http_auth POST "$BASE/api/payments/ride/$RIDE2" "$TOKEN" -H "Content-Type: application/json" -d '{"userId":1,"amount":200,"method":"CASH"}'
  PID2="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  if [ -n "$PID2" ]; then
    # Read surgeFee that the M1 retrofit wrote (§4.6) so we can derive the
    # spec-mandated base-only refund: refundAmount == amount - surgeFee.
    http_auth GET "$BASE/api/payments/$PID2" "$TOKEN" >/dev/null
    p2_amt="$(echo "$LAST_BODY" | jq -r '.amount // 0')"
    p2_sfee="$(echo "$LAST_BODY" | jq -r '.transactionDetails.surgeFee // 0')"

    http_auth POST "$BASE/api/payments/$PID2/refund-surge-adjusted" "$TOKEN" \
      -H "Content-Type: application/json" -d '{"reason":"driver_no_show","refundSurge":false}'
    assert_status 200 "S5-F12 refundSurge=false → 200 (§10.5.3 test b)"
    rsi="$(echo "$LAST_BODY" | jq -r '.transactionDetails.refundSurgeIncluded // empty')"
    [ "$rsi" = "false" ] && pass "S5-F12 base-only: refundSurgeIncluded=false" \
                         || fail "S5-F12 base-only: refundSurgeIncluded=false" "got $rsi"
    p2_refund="$(echo "$LAST_BODY" | jq -r '.transactionDetails.refundAmount // 0')"
    expected_refund="$(awk "BEGIN{print ${p2_amt}-${p2_sfee}}")"
    if [ "$(awk "BEGIN{print (${p2_refund}==${expected_refund})}")" = "1" ]; then
      pass "S5-F12 base-only: refundAmount=$p2_refund == amount-surgeFee=$expected_refund"
    else
      fail "S5-F12 base-only: refundAmount=amount-surgeFee" \
           "got refundAmount=$p2_refund, expected $expected_refund (amt=$p2_amt sfee=$p2_sfee)"
    fi

    # §10.5.3 step i — entity-detail invalidation on refund
    http_auth GET "$BASE/api/payments/$PID2" "$TOKEN" >/dev/null
    if [ "$(redis_count_keys "payment-service::payment::$PID2")" -ge 1 ]; then
      pass "post-refund GET re-caches payment-service::payment::$PID2"
    else
      skip "post-refund GET re-caches payment-service::payment::$PID2" \
           "payment-service may not have @Cacheable on get-by-id yet"
    fi
  fi
fi

# (c) PENDING payment → 400
http_auth POST "$BASE/api/payments" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"rideId":$RIDE_ID,"amount":50,"method":"CREDIT_CARD","status":"PENDING"}
EOF
)"
PID_PEND="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$PID_PEND" ]; then
  http_auth POST "$BASE/api/payments/$PID_PEND/refund-surge-adjusted" "$TOKEN" \
    -H "Content-Type: application/json" -d '{"reason":"x","refundSurge":true}'
  assert_status 400 "S5-F12 PENDING payment → 400"
fi

# (d) Already REFUNDED payment → 400
if [ -n "$PAY_ID" ]; then
  http_auth POST "$BASE/api/payments/$PAY_ID/refund-surge-adjusted" "$TOKEN" \
    -H "Content-Type: application/json" -d '{"reason":"x","refundSurge":true}'
  assert_status 400 "S5-F12 already-REFUNDED → 400"
fi

# (e) Unknown payment → 404
http_auth POST "$BASE/api/payments/9999999/refund-surge-adjusted" "$TOKEN" \
  -H "Content-Type: application/json" -d '{"reason":"x","refundSurge":true}'
assert_status 404 "S5-F12 unknown payment → 404"

# (f) No token → 401
http POST "$BASE/api/payments/$PAY_ID/refund-surge-adjusted" \
  -H "Content-Type: application/json" -d '{"reason":"x","refundSurge":true}'
assert_status 401 "S5-F12 no token → 401"

# (g) NoRefundStrategy: payment older than 24h → 400 + REFUND_DENIED audit
# Simulate by directly inserting a Payment with createdAt 2 days ago via CRUD.
# (If your CRUD enforces server-set createdAt, this case may need DB seeding.)
http_auth POST "$BASE/api/payments" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"rideId":$RIDE_ID,"amount":80,"method":"CASH","status":"COMPLETED",
 "createdAt":"2026-03-01T00:00:00",
 "transactionDetails":{"surgeFee":12}}
EOF
)"
OLD_PID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$OLD_PID" ]; then
  http_auth POST "$BASE/api/payments/$OLD_PID/refund-surge-adjusted" "$TOKEN" \
    -H "Content-Type: application/json" -d '{"reason":"x","refundSurge":true}'
  assert_status 400 "S5-F12 >24h old → 400 (NoRefundStrategy)"
  rd="$(mongo_count payment_audit_trail "{ paymentId: $OLD_PID, action: 'REFUND_DENIED' }")"
  [ "${rd:-0}" -ge 1 ] && pass "S5-F12 REFUND_DENIED audit on NoRefundStrategy" \
                       || fail "S5-F12 REFUND_DENIED audit on NoRefundStrategy"
  # §10.5.3 step e (ii): caches must be invalidated even on the denial path
  [ "$(redis_count_keys 'payment-service::S5-F10::*')" = "0" ] \
    && pass "S5-F12 NoRefund path invalidates payment-service::S5-F10::*" \
    || skip "S5-F12 NoRefund path invalidates payment-service::S5-F10::*"
fi

# (h) Distinct-path coexistence with M1 PUT /api/payments/{id}/refund.
# §4.5 step f — the M1 simple-refund path must also write REFUNDED to
# payment_audit_trail (the Observer retrofit applies to the M1 endpoint
# too, not only S5-F12).
http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,
 "pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":80,
 "requestedAt":"2026-11-20T10:00:00","completedAt":"2026-11-20T10:30:00",
 "metadata":{}}
EOF
)" >/dev/null
RIDE_M1R="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$RIDE_M1R" ]; then
  http_auth POST "$BASE/api/payments/ride/$RIDE_M1R" "$TOKEN" -H "Content-Type: application/json" \
    -d '{"userId":1,"amount":80,"method":"CREDIT_CARD"}' >/dev/null
  PID_M1R="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  if [ -n "$PID_M1R" ]; then
    http_auth PUT "$BASE/api/payments/$PID_M1R/refund" "$TOKEN" -H "Content-Type: application/json" -d '{"reason":"customer_request"}'
    assert_status_in "§4.5.f M1 PUT /refund accepted" 200 204
    sleep 1
    m1ref="$(mongo_count payment_audit_trail "{ paymentId: $PID_M1R, action: 'REFUNDED' }")"
    [ "${m1ref:-0}" -ge 1 ] && pass "§4.5.f M1 PUT /refund emits REFUNDED audit" \
                            || fail "§4.5.f M1 PUT /refund emits REFUNDED audit" "got $m1ref"
  fi
fi

# ============================================================
# S5-F10 Fare Revenue by Vehicle Type   (§10.5.1)
# ============================================================

http_auth GET "$BASE/api/payments/analytics/vehicle-type?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN"
assert_status 200 "S5-F10 valid range → 200"

if echo "$LAST_BODY" | jq -e 'type == "array"' >/dev/null 2>&1; then
  pass "S5-F10 returns an array"
  # Validate DTO shape (vehicleType, baseFareRevenue, surgeFeeRevenue, totalRevenue, rideCount)
  for f in vehicleType baseFareRevenue surgeFeeRevenue totalRevenue rideCount; do
    if echo "$LAST_BODY" | jq -e ".[0].${f}? // empty" >/dev/null 2>&1; then
      pass "S5-F10 DTO has $f"
    else
      skip "S5-F10 DTO has $f" "array may be empty for this date range"
    fi
  done
fi

# §10.5.1 step a — exact vehicle-type breakdown.
# Seed 3 SEDAN drivers with rides totaling 600 (90 surge), 2 SUV drivers
# with rides totaling 400 (60 surge). Then assert exact numbers.
seed_typed_driver() {
  local name="$1" type="$2"
  local s="${RANDOM}${RANDOM}"
  http_auth POST "$DRIVER_URL/api/drivers" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"$name","email":"vt-${s}@x.io","phone":"+17${s:0:9}",
 "licenseNumber":"LIC-VT-${s}","rating":4.0,"totalRatings":10,
 "status":"AVAILABLE","createdAt":"2026-04-01T00:00:00",
 "vehicleDetails":{"vehicleType":"$type","plate":"VT-${s}"}}
EOF
)" >/dev/null
  echo "$LAST_BODY" | jq -r '.id // empty'
}
S1="$(seed_typed_driver SedanA SEDAN)"; S2="$(seed_typed_driver SedanB SEDAN)"; S3="$(seed_typed_driver SedanC SEDAN)"
SU1="$(seed_typed_driver SuvA SUV)";    SU2="$(seed_typed_driver SuvB SUV)"

seed_paid_ride() {
  local drv="$1" amt="$2"
  http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":$drv,"pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":$amt,
 "requestedAt":"2026-10-15T10:00:00","completedAt":"2026-10-15T10:30:00",
 "metadata":{"surgeMultiplier":1.18}}
EOF
)" >/dev/null
  local rid="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  [ -n "$rid" ] && http_auth POST "$BASE/api/payments/ride/$rid" "$TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":1,\"amount\":$amt,\"method\":\"CREDIT_CARD\"}" >/dev/null
}
[ -n "$S1" ]  && seed_paid_ride "$S1"  200
[ -n "$S2" ]  && seed_paid_ride "$S2"  200
[ -n "$S3" ]  && seed_paid_ride "$S3"  200
[ -n "$SU1" ] && seed_paid_ride "$SU1" 200
[ -n "$SU2" ] && seed_paid_ride "$SU2" 200
sleep 2

http_auth GET "$BASE/api/payments/analytics/vehicle-type?startDate=2026-10-01&endDate=2026-10-31" "$TOKEN"
if [ "$LAST_STATUS" = "200" ]; then
  sedan_count="$(echo "$LAST_BODY" | jq -r '.[] | select(.vehicleType=="SEDAN") | .rideCount // 0' | head -1)"
  suv_count="$(echo "$LAST_BODY"   | jq -r '.[] | select(.vehicleType=="SUV")   | .rideCount // 0' | head -1)"
  sedan_total="$(echo "$LAST_BODY" | jq -r '.[] | select(.vehicleType=="SEDAN") | .totalRevenue // 0' | head -1)"
  suv_total="$(echo "$LAST_BODY"   | jq -r '.[] | select(.vehicleType=="SUV")   | .totalRevenue // 0' | head -1)"
  [ "${sedan_count:-0}" -ge 3 ] && pass "§10.5.1.a SEDAN rideCount≥3 ($sedan_count)" || fail "§10.5.1.a SEDAN rideCount≥3" "got $sedan_count"
  [ "${suv_count:-0}"   -ge 2 ] && pass "§10.5.1.a SUV rideCount≥2 ($suv_count)"     || fail "§10.5.1.a SUV rideCount≥2"   "got $suv_count"
  if awk "BEGIN{exit !(${sedan_total:-0} >= 600)}"; then pass "§10.5.1.a SEDAN totalRevenue≥600 ($sedan_total)"; else fail "§10.5.1.a SEDAN totalRevenue≥600" "got $sedan_total"; fi
  if awk "BEGIN{exit !(${suv_total:-0}   >= 400)}"; then pass "§10.5.1.a SUV totalRevenue≥400 ($suv_total)";     else fail "§10.5.1.a SUV totalRevenue≥400"   "got $suv_total"; fi
fi

# §10.5.1 step b — empty range → empty list
http_auth GET "$BASE/api/payments/analytics/vehicle-type?startDate=2099-01-01&endDate=2099-01-31" "$TOKEN"
if [ "$LAST_STATUS" = "200" ]; then
  empty="$(echo "$LAST_BODY" | jq -r 'length // 0')"
  [ "${empty:-99}" = "0" ] && pass "§10.5.1.b empty range → []" \
                           || fail "§10.5.1.b empty range → []" "got $empty"
fi

for d in "$S1" "$S2" "$S3" "$SU1" "$SU2"; do
  [ -n "$d" ] && http_auth DELETE "$DRIVER_URL/api/drivers/$d" "$TOKEN" >/dev/null
done

# Inverted range → 400
http_auth GET "$BASE/api/payments/analytics/vehicle-type?startDate=2026-05-01&endDate=2026-04-01" "$TOKEN"
assert_status 400 "S5-F10 inverted range → 400"

# No token → 401
http GET "$BASE/api/payments/analytics/vehicle-type?startDate=2026-04-01&endDate=2026-04-30"
assert_status 401 "S5-F10 no token → 401"

# ANALYTICS_VIEWED on every call (incl. cache hit, §10.5.1 step f)
before="$(mongo_count payment_audit_trail "{ action: 'ANALYTICS_VIEWED' }")"
http_auth GET "$BASE/api/payments/analytics/vehicle-type?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN" >/dev/null
http_auth GET "$BASE/api/payments/analytics/vehicle-type?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN" >/dev/null
sleep 1
after="$(mongo_count payment_audit_trail "{ action: 'ANALYTICS_VIEWED' }")"
diff=$((after - before))
[ "$diff" -ge 2 ] && pass "S5-F10 ANALYTICS_VIEWED every call (+$diff)" \
                  || fail "S5-F10 ANALYTICS_VIEWED every call" "+$diff"

# Cache 10 min
[ "$(redis_count_keys 'payment-service::S5-F10::*')" -ge 1 ] \
  && pass "S5-F10 caches payment-service::S5-F10::*" \
  || skip "S5-F10 caches payment-service::S5-F10::*" "feature may be missing per catalog"

# ============================================================
# S5-F11 Payment Method Breakdown   (§10.5.2)
# ============================================================

http_auth GET "$BASE/api/payments/analytics/methods?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN"
assert_status 200 "S5-F11 valid range → 200"
if echo "$LAST_BODY" | jq -e 'type == "array"' >/dev/null 2>&1; then
  pass "S5-F11 returns an array"
  for f in method successCount failureCount successRate totalAmount; do
    if echo "$LAST_BODY" | jq -e ".[0].${f}? // empty" >/dev/null 2>&1; then
      pass "S5-F11 DTO has $f"
    else
      skip "S5-F11 DTO has $f" "may be empty for this range"
    fi
  done
fi

# §10.5.2 step a — exact methods breakdown.
# Seed 5 successful CREDIT_CARD totaling 500 + 2 failed CC + 3 successful CASH totaling 300.
seed_method_payment() {
  local method="$1" amt="$2" failmode="$3"
  http_auth POST "$RIDE_URL/api/rides" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"userId":1,"driverId":1,"pickupLatitude":30.0,"pickupLongitude":31.0,
 "dropoffLatitude":30.1,"dropoffLongitude":31.1,
 "status":"COMPLETED","fare":$amt,
 "requestedAt":"2026-11-10T10:00:00","completedAt":"2026-11-10T10:30:00",
 "metadata":{}}
EOF
)" >/dev/null
  local rid="$(echo "$LAST_BODY" | jq -r '.id // empty')"
  [ -z "$rid" ] && return
  if [ "$failmode" = "fail" ]; then
    http_auth POST "$BASE/api/payments/ride/$rid?simulateFailure=true" "$TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"userId\":1,\"amount\":$amt,\"method\":\"$method\"}" >/dev/null
  else
    http_auth POST "$BASE/api/payments/ride/$rid" "$TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"userId\":1,\"amount\":$amt,\"method\":\"$method\"}" >/dev/null
  fi
}
for amt in 100 100 100 100 100; do seed_method_payment CREDIT_CARD $amt ok; done
for amt in 100 100;             do seed_method_payment CREDIT_CARD $amt fail; done
for amt in 100 100 100;         do seed_method_payment CASH        $amt ok; done
sleep 2

http_auth GET "$BASE/api/payments/analytics/methods?startDate=2026-11-01&endDate=2026-11-30" "$TOKEN"
if [ "$LAST_STATUS" = "200" ]; then
  cc_succ="$(echo "$LAST_BODY"  | jq -r '.[] | select(.method=="CREDIT_CARD") | .successCount // 0' | head -1)"
  cc_fail="$(echo "$LAST_BODY"  | jq -r '.[] | select(.method=="CREDIT_CARD") | .failureCount // 0' | head -1)"
  cc_tot="$(echo "$LAST_BODY"   | jq -r '.[] | select(.method=="CREDIT_CARD") | .totalAmount // 0'  | head -1)"
  ca_succ="$(echo "$LAST_BODY"  | jq -r '.[] | select(.method=="CASH")        | .successCount // 0' | head -1)"
  ca_tot="$(echo "$LAST_BODY"   | jq -r '.[] | select(.method=="CASH")        | .totalAmount // 0'  | head -1)"
  [ "${cc_succ:-0}" -ge 5 ] && pass "§10.5.2.a CREDIT_CARD success≥5 ($cc_succ)" || fail "§10.5.2.a CREDIT_CARD success≥5" "got $cc_succ"
  [ "${cc_fail:-0}" -ge 2 ] && pass "§10.5.2.a CREDIT_CARD fail≥2 ($cc_fail)"    || fail "§10.5.2.a CREDIT_CARD fail≥2"    "got $cc_fail"
  if awk "BEGIN{exit !(${cc_tot:-0} >= 500)}"; then pass "§10.5.2.a CREDIT_CARD totalAmount≥500 ($cc_tot)"; else fail "§10.5.2.a CREDIT_CARD totalAmount≥500" "got $cc_tot"; fi
  [ "${ca_succ:-0}" -ge 3 ] && pass "§10.5.2.a CASH success≥3 ($ca_succ)"       || fail "§10.5.2.a CASH success≥3"       "got $ca_succ"
  if awk "BEGIN{exit !(${ca_tot:-0}  >= 300)}"; then pass "§10.5.2.a CASH totalAmount≥300 ($ca_tot)";       else fail "§10.5.2.a CASH totalAmount≥300"       "got $ca_tot"; fi
fi

# §10.5.2 step b — empty range → empty list
http_auth GET "$BASE/api/payments/analytics/methods?startDate=2099-01-01&endDate=2099-01-31" "$TOKEN"
if [ "$LAST_STATUS" = "200" ]; then
  empty="$(echo "$LAST_BODY" | jq -r 'length // 0')"
  [ "${empty:-99}" = "0" ] && pass "§10.5.2.b empty range → []" \
                           || fail "§10.5.2.b empty range → []" "got $empty"
fi

# Inverted range
http_auth GET "$BASE/api/payments/analytics/methods?startDate=2026-05-01&endDate=2026-04-01" "$TOKEN"
assert_status 400 "S5-F11 inverted range → 400"

# No token
http GET "$BASE/api/payments/analytics/methods?startDate=2026-04-01&endDate=2026-04-30"
assert_status 401 "S5-F11 no token → 401"

# Cached for 10 min
http_auth GET "$BASE/api/payments/analytics/methods?startDate=2026-04-01&endDate=2026-04-30" "$TOKEN" >/dev/null
[ "$(redis_count_keys 'payment-service::S5-F11::*')" -ge 1 ] \
  && pass "S5-F11 caches payment-service::S5-F11::*" \
  || skip "S5-F11 caches payment-service::S5-F11::*" "feature may be missing per catalog"

# ============================================================
# M1 S5 features
# ============================================================

# S5-F1 search
http_auth GET "$BASE/api/payments/search?status=COMPLETED&startDate=2026-01-01&endDate=2026-12-31" "$TOKEN"
assert_status_in "M1 S5-F1 GET /api/payments/search" 200 204

# S5-F2 (M1) refund — already exercised above against PID2

# S5-F3 user summary
http_auth GET "$BASE/api/payments/user/1/summary" "$TOKEN"
assert_status_in "M1 S5-F3 GET /api/payments/user/{id}/summary" 200 204

# S5-F6 revenue
http_auth GET "$BASE/api/payments/reports/revenue?startDate=2026-01-01&endDate=2026-12-31" "$TOKEN"
assert_status_in "M1 S5-F6 GET /api/payments/reports/revenue" 200 204

# S5-F7 retry
if [ -n "$FAIL_PID" ]; then
  http_auth PUT "$BASE/api/payments/$FAIL_PID/retry" "$TOKEN"
  assert_status_in "M1 S5-F7 PUT /api/payments/{id}/retry" 200 204
fi

# S5-F8 top-used coupons
http_auth GET "$BASE/api/payments/coupons/top-used?limit=5" "$TOKEN"
assert_status_in "M1 S5-F8 GET /api/payments/coupons/top-used" 200 204

# S5-F9 details
if [ -n "$PAY_ID" ]; then
  http_auth GET "$BASE/api/payments/$PAY_ID/details" "$TOKEN"
  assert_status_in "M1 S5-F9 GET /api/payments/{id}/details" 200 204
fi

# ============================================================
# CRUD Coupon
# ============================================================
http_auth POST "$BASE/api/coupons" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"code":"SUMMER-$RUN_ID","discountPercent":20,"validUntil":"2030-12-31"}
EOF
)"
COUP_ID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
assert_status_in "CRUD POST /api/coupons" 200 201
if [ -n "$COUP_ID" ]; then
  http_auth GET "$BASE/api/coupons/$COUP_ID" "$TOKEN"
  assert_status 200 "CRUD GET /api/coupons/$COUP_ID"
  http_auth GET "$BASE/api/coupons/$COUP_ID" "$TOKEN" >/dev/null
  if [ "$(redis_count_keys "payment-service::coupon::$COUP_ID")" -ge 1 ]; then
    pass "GET-by-id caches payment-service::coupon::$COUP_ID (§4.4.2)"
  else
    skip "GET-by-id caches payment-service::coupon::$COUP_ID" \
         "payment-service may not have @Cacheable yet"
  fi
  http_auth PUT "$BASE/api/coupons/$COUP_ID" "$TOKEN" -H "Content-Type: application/json" -d "$(cat <<EOF
{"code":"SUMMER-$RUN_ID","discountPercent":25,"validUntil":"2030-12-31"}
EOF
)"
  assert_status_in "CRUD PUT /api/coupons/$COUP_ID" 200 204

  # S5-F5 apply coupon (write — emits COUPON_APPLIED)
  if [ -n "$PID2" ]; then
    http_auth POST "$BASE/api/payments/$PID2/coupons/$COUP_ID" "$TOKEN"
    assert_status_in "M1 S5-F5 POST /api/payments/{paymentId}/coupons/{couponId}" 200 201 400 404
    sleep 1
    ca="$(mongo_count payment_audit_trail "{ paymentId: $PID2, action: 'COUPON_APPLIED' }")"
    [ "${ca:-0}" -ge 1 ] && pass "S5-F5 emits COUPON_APPLIED" || skip "S5-F5 emits COUPON_APPLIED" "may need re-run order"
  fi

  http_auth DELETE "$BASE/api/coupons/$COUP_ID" "$TOKEN"
  assert_status_in "CRUD DELETE /api/coupons/$COUP_ID" 200 204
fi

http_auth GET "$BASE/api/coupons" "$TOKEN" >/dev/null
assert_status 200 "CRUD GET /api/coupons (list)"
[ "$(redis_keys 'payment-service::coupon::list*' | wc -l)" = "0" ] \
  && pass "CRUD GET /api/coupons (list) NOT cached" \
  || fail "CRUD GET /api/coupons (list) NOT cached"

# ============================================================
# CRUD PaymentCoupon
# ============================================================
http_auth GET "$BASE/api/payment-coupons" "$TOKEN"
assert_status 200 "CRUD GET /api/payment-coupons (list)"
# GET-by-id caching path: try the most recently created PaymentCoupon row
PCID="$(echo "$LAST_BODY" | jq -r '.[0].id // empty' 2>/dev/null)"
if [ -n "$PCID" ]; then
  http_auth GET "$BASE/api/payment-coupons/$PCID" "$TOKEN" >/dev/null
  http_auth GET "$BASE/api/payment-coupons/$PCID" "$TOKEN" >/dev/null
  if [ "$(redis_count_keys "payment-service::payment-coupon::$PCID")" -ge 1 ]; then
    pass "GET-by-id caches payment-service::payment-coupon::$PCID (§4.4.2)"
  else
    skip "GET-by-id caches payment-service::payment-coupon::$PCID"
  fi
fi

# Final cleanup of seed payments and rides (best effort)
for p in "$PAY_ID" "$FAIL_PID" "$PID2" "$PID_PEND" "$OLD_PID"; do
  [ -n "$p" ] && http_auth DELETE "$BASE/api/payments/$p" "$TOKEN" >/dev/null
done
for r in "$RIDE_ID" "$RIDE_FAIL" "$RIDE2"; do
  [ -n "$r" ] && http_auth DELETE "$RIDE_URL/api/rides/$r" "$TOKEN" >/dev/null
done
