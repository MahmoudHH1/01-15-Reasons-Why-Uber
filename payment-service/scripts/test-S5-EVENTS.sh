#!/usr/bin/env bash
# End-to-end test for S5-EVENTS — payment-service RabbitMQ topology, Feign refactors
# (S5-F3, S5-F4, S5-F10), saga consumers (ride.completed / ride.cancelled),
# idempotency, auth guard, and cache.
#
# Spec citations: uber-m3.md §7-EVENTS, §8 (saga-events.md), feign-contracts.md §N+1
#
# Prerequisites:
#   docker compose up -d (all services running: payment-service, user-service, rabbitmq)
#   OR minikube with relevant pods Running and ports forwarded.
#
# Override config via env vars — defaults match docker-compose.yaml.
# Exit code = number of FAIL assertions. 0 = all green.

set -u

PAYMENT_SVC="${PAYMENT_SVC:-http://localhost:8085}"
USER_SVC="${USER_SVC:-http://localhost:8081}"
RABBIT_API="${RABBIT_API:-http://localhost:15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
VHOST="${VHOST:-%2F}"
WAIT_SEC="${WAIT_SEC:-2}"

RUN_ID="$(date +%s)$$"
SHORT_ID="${RUN_ID: -8}"
TEST_EMAIL="tester-s5ev-${SHORT_ID}@uber.local"
TEST_PHONE="0100${SHORT_ID: -7}"
SAGA_RIDE_ID=$(( SHORT_ID % 900000 + 100000 ))
SAGA_USER_ID=$(( SHORT_ID % 90000  + 10000  ))
NULL_FARE_RIDE_ID=$(( SHORT_ID % 800000 + 200000 ))
ORPHAN_RIDE_ID=$(( SHORT_ID % 700000 + 300000 ))

PASS=0; FAIL=0

report() {
  local name="$1" cond="$2" detail="${3:-}"
  if [ "$cond" = "1" ]; then PASS=$((PASS+1)); echo "PASS  $name"
  else FAIL=$((FAIL+1)); echo "FAIL  $name${detail:+ -- $detail}"; fi
}

rabbit() { curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" "$@"; }

publish_to_ride_events() {
  local routing_key="$1" payload_json="$2"
  local body
  body=$(python3 -c "import json,sys; print(json.dumps({
    'routing_key': sys.argv[1],
    'payload': sys.argv[2],
    'payload_encoding': 'string',
    'properties': {'content_type': 'application/json', 'delivery_mode': 2}
  }))" "$routing_key" "$payload_json" 2>/dev/null)
  rabbit -X POST "$RABBIT_API/api/exchanges/$VHOST/ride.events/publish" \
    -H "Content-Type: application/json" -d "$body" > /dev/null 2>&1
}

json_ride_completed() {
  python3 -c "import json,sys
print(json.dumps({'rideId':int(sys.argv[1]),'userId':int(sys.argv[2]),'driverId':5,'fare':float(sys.argv[3])}))" \
    "$1" "$2" "$3" 2>/dev/null
}

json_ride_cancelled() {
  python3 -c "import json,sys
print(json.dumps({'rideId':int(sys.argv[1]),'userId':int(sys.argv[2]),'driverId':5,'reason':sys.argv[3]}))" \
    "$1" "$2" "$3" 2>/dev/null
}

# ── 0. Health (uber-m3.md §5 — public endpoint) ──────────────────────────────
echo "=== Health ==="
SC=$(curl -s -o /dev/null -w "%{http_code}" "$PAYMENT_SVC/api/payments/health")
[ "$SC" = "200" ] && report "(0) GET /api/payments/health public → 200" 1 \
                  || report "(0) GET /api/payments/health public → 200" 0 "got $SC"
[ "$FAIL" -gt 0 ] && { echo "payment-service unreachable — aborting"; exit "$FAIL"; }

# ── Obtain JWT ────────────────────────────────────────────────────────────────
echo; echo "=== Obtain JWT ==="
TOKEN=$(curl -s -X POST "$USER_SVC/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"S5Events Tester\",\"email\":\"$TEST_EMAIL\",\"password\":\"Test1234!\",\"phone\":\"$TEST_PHONE\"}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
[ -n "$TOKEN" ] && echo "  JWT obtained" || echo "  WARN: could not get JWT from user-service — auth-dependent tests will be skipped"

# ── 1. Auth guard (uber-m3.md §5, CLAUDE.md JWT contract) ───────────────────
echo; echo "=== Auth guard ==="

SC=$(curl -s -o /dev/null -w "%{http_code}" "$PAYMENT_SVC/api/payments/user/1/summary")
if [ "$SC" = "401" ] || [ "$SC" = "403" ]; then
  report "(1a) /user/{id}/summary without token → 401/403" 1
else
  report "(1a) /user/{id}/summary without token → 401/403" 0 "got $SC"
fi

SC=$(curl -s -o /dev/null -w "%{http_code}" "$PAYMENT_SVC/api/payments/user/1/summary" \
  -H "Authorization: Bearer garbage.invalid.token")
if [ "$SC" = "401" ] || [ "$SC" = "403" ]; then
  report "(1b) /user/{id}/summary with garbage token → 401/403" 1
else
  report "(1b) /user/{id}/summary with garbage token → 401/403" 0 "got $SC"
fi

SC=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$PAYMENT_SVC/api/payments/ride/1" \
  -H "Content-Type: application/json" -d '{"method":"CREDIT_CARD"}')
if [ "$SC" = "401" ] || [ "$SC" = "403" ]; then
  report "(1c) POST /ride/{id} without token → 401/403" 1
else
  report "(1c) POST /ride/{id} without token → 401/403" 0 "got $SC"
fi

# ── 2. RabbitMQ topology (uber-m3.md §7-EVENTS, saga-events.md) ──────────────
echo; echo "=== RabbitMQ topology ==="
RABBIT_UP=0
RABBIT_SC=$(curl -s -o /dev/null -w "%{http_code}" -u "${RABBIT_USER}:${RABBIT_PASS}" "$RABBIT_API/api/overview")
[ "$RABBIT_SC" = "200" ] && RABBIT_UP=1

if [ "$RABBIT_UP" = "1" ]; then
  # 2a — payment.events TopicExchange (publisher)
  ETYPE=$(rabbit "$RABBIT_API/api/exchanges/$VHOST/payment.events" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('type',''))" 2>/dev/null)
  [ "$ETYPE" = "topic" ] && report "(2a) payment.events TopicExchange declared" 1 \
                          || report "(2a) payment.events TopicExchange declared" 0 "type='$ETYPE'"

  # 2b — ride.events TopicExchange (consumer)
  ETYPE2=$(rabbit "$RABBIT_API/api/exchanges/$VHOST/ride.events" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('type',''))" 2>/dev/null)
  [ "$ETYPE2" = "topic" ] && report "(2b) ride.events TopicExchange declared" 1 \
                           || report "(2b) ride.events TopicExchange declared" 0 "type='$ETYPE2'"

  # 2c — payment.dlx DirectExchange for DLQ routing
  DTYPE=$(rabbit "$RABBIT_API/api/exchanges/$VHOST/payment.dlx" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('type',''))" 2>/dev/null)
  [ "$DTYPE" = "direct" ] && report "(2c) payment.dlx DirectExchange declared" 1 \
                           || report "(2c) payment.dlx DirectExchange declared" 0 "type='$DTYPE'"

  # 2d — payment.saga-listener durable with x-dead-letter-exchange=payment.dlx
  DLX=$(rabbit "$RABBIT_API/api/queues/$VHOST/payment.saga-listener" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('arguments',{}).get('x-dead-letter-exchange',''))" 2>/dev/null)
  [ "$DLX" = "payment.dlx" ] && report "(2d) payment.saga-listener DLX=payment.dlx" 1 \
                               || report "(2d) payment.saga-listener DLX=payment.dlx" 0 "got '$DLX'"

  # 2e — payment.saga-listener.dlq exists (durable)
  DLQ_DURABLE=$(rabbit "$RABBIT_API/api/queues/$VHOST/payment.saga-listener.dlq" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('durable',''))" 2>/dev/null)
  [ "$DLQ_DURABLE" = "True" ] && report "(2e) payment.saga-listener.dlq durable queue" 1 \
                               || report "(2e) payment.saga-listener.dlq durable queue" 0 "durable='$DLQ_DURABLE'"

  # 2f — binding: ride.events → payment.saga-listener on ride.completed
  BINDINGS=$(rabbit "$RABBIT_API/api/bindings/$VHOST/e/ride.events/q/payment.saga-listener" \
    | python3 -c "import sys,json;print(' '.join(b.get('routing_key','') for b in json.load(sys.stdin)))" 2>/dev/null)
  echo "$BINDINGS" | grep -q "ride.completed" \
    && report "(2f) payment.saga-listener bound to ride.events/ride.completed" 1 \
    || report "(2f) payment.saga-listener bound to ride.events/ride.completed" 0 "bindings='$BINDINGS'"

  # 2g — binding: ride.events → payment.saga-listener on ride.cancelled
  echo "$BINDINGS" | grep -q "ride.cancelled" \
    && report "(2g) payment.saga-listener bound to ride.events/ride.cancelled" 1 \
    || report "(2g) payment.saga-listener bound to ride.events/ride.cancelled" 0 "bindings='$BINDINGS'"
else
  echo "  WARN: RabbitMQ management API unreachable at $RABBIT_API — topology tests skipped"
  for t in 2a 2b 2c 2d 2e 2f 2g; do report "($t) RabbitMQ topology" 0 "RabbitMQ unavailable"; done
fi

# ── 3. S5-F3 Feign refactor: user-service existence (feign-contracts.md) ─────
echo; echo "=== S5-F3 Feign refactor (user existence) ==="
if [ -n "$TOKEN" ]; then
  SC=$(curl -s -o /dev/null -w "%{http_code}" \
    "$PAYMENT_SVC/api/payments/user/999999999/summary" \
    -H "Authorization: Bearer $TOKEN")
  [ "$SC" = "404" ] && report "(3a) /user/999999999/summary → 404 (Feign.NotFound)" 1 \
                     || report "(3a) /user/999999999/summary → 404 (Feign.NotFound)" 0 "got $SC"
else
  report "(3a) /user/999999999/summary → 404" 0 "skipped — no JWT"
fi

# ── 4. S5-F4 Feign refactor: ride-service existence (feign-contracts.md) ─────
echo; echo "=== S5-F4 Feign refactor (ride existence) ==="
if [ -n "$TOKEN" ]; then
  SC=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$PAYMENT_SVC/api/payments/ride/999999999" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"method":"CREDIT_CARD","cardLastFour":"4242"}')
  [ "$SC" = "404" ] && report "(4a) POST /ride/999999999 → 404 (Feign.NotFound)" 1 \
                     || report "(4a) POST /ride/999999999 → 404 (Feign.NotFound)" 0 "got $SC"
else
  report "(4a) POST /ride/999999999 → 404" 0 "skipped — no JWT"
fi

# ── 5. S5-F10 Feign refactor: vehicle-type revenue (feign-contracts.md N+1 cap) ─
echo; echo "=== S5-F10 Feign refactor (vehicle-type revenue) ==="
if [ -n "$TOKEN" ]; then
  # 5a — valid date range → 200 JSON array
  SC=$(curl -s -o /dev/null -w "%{http_code}" \
    "$PAYMENT_SVC/api/payments/analytics/vehicle-type?startDate=2024-01-01&endDate=2024-12-31" \
    -H "Authorization: Bearer $TOKEN")
  [ "$SC" = "200" ] && report "(5a) analytics/vehicle-type valid dates → 200" 1 \
                     || report "(5a) analytics/vehicle-type valid dates → 200" 0 "got $SC"

  # 5b — startDate > endDate → 400 (uber-m3.md §7 error paths)
  SC=$(curl -s -o /dev/null -w "%{http_code}" \
    "$PAYMENT_SVC/api/payments/analytics/vehicle-type?startDate=2025-01-01&endDate=2024-01-01" \
    -H "Authorization: Bearer $TOKEN")
  [ "$SC" = "400" ] && report "(5b) analytics/vehicle-type startDate>endDate → 400" 1 \
                     || report "(5b) analytics/vehicle-type startDate>endDate → 400" 0 "got $SC"

  # 5c — cache: second identical call returns 200 (docs/m3/cache-matrix.md S5-F10)
  SC2=$(curl -s -o /dev/null -w "%{http_code}" \
    "$PAYMENT_SVC/api/payments/analytics/vehicle-type?startDate=2024-01-01&endDate=2024-12-31" \
    -H "Authorization: Bearer $TOKEN")
  [ "$SC2" = "200" ] && report "(5c) analytics/vehicle-type second call (cache hit) → 200" 1 \
                      || report "(5c) analytics/vehicle-type second call (cache hit) → 200" 0 "got $SC2"
else
  for t in 5a 5b 5c; do report "($t) S5-F10" 0 "skipped — no JWT"; done
fi

# ── 6. Saga: ride.completed → PENDING payment + payment.initiated ─────────────
# Spec: saga-events.md forward path; uber-m3.md:1257-1280
echo; echo "=== Saga forward path: ride.completed ==="

if [ "$RABBIT_UP" = "1" ]; then
  # Set up a capture queue to intercept payment.initiated BEFORE publishing
  CAPTURE_Q="test.capture.initiated.${SHORT_ID}"
  rabbit -X PUT "$RABBIT_API/api/queues/$VHOST/$CAPTURE_Q" \
    -H "Content-Type: application/json" -d '{"durable":false,"auto_delete":true}' > /dev/null 2>&1
  rabbit -X POST "$RABBIT_API/api/bindings/$VHOST/e/payment.events/q/$CAPTURE_Q" \
    -H "Content-Type: application/json" -d '{"routing_key":"payment.initiated"}' > /dev/null 2>&1

  # Publish ride.completed
  RC_PAYLOAD=$(json_ride_completed "$SAGA_RIDE_ID" "$SAGA_USER_ID" "42.50")
  publish_to_ride_events "ride.completed" "$RC_PAYLOAD"
  sleep "$WAIT_SEC"

  # 6a — payment.initiated published to payment.events
  INITIATED_COUNT=$(rabbit "$RABBIT_API/api/queues/$VHOST/$CAPTURE_Q" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('messages',0))" 2>/dev/null)
  [ "$INITIATED_COUNT" -ge 1 ] 2>/dev/null \
    && report "(6a) ride.completed → payment.initiated published" 1 \
    || report "(6a) ride.completed → payment.initiated published" 0 "messages in capture queue=$INITIATED_COUNT"

  # Cleanup capture queue
  rabbit -X DELETE "$RABBIT_API/api/queues/$VHOST/$CAPTURE_Q" > /dev/null 2>&1

  if [ -n "$TOKEN" ]; then
    SEARCH=$(curl -s \
      "$PAYMENT_SVC/api/payments/search?status=PENDING&userId=${SAGA_USER_ID}" \
      -H "Authorization: Bearer $TOKEN")

    # 6b — PENDING payment row created for this rideId
    FOUND=$(echo "$SEARCH" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('True' if any(p.get('rideId')==${SAGA_RIDE_ID} for p in d) else 'False')" 2>/dev/null)
    [ "$FOUND" = "True" ] && report "(6b) ride.completed → PENDING payment created" 1 \
                           || report "(6b) ride.completed → PENDING payment created" 0 "found=$FOUND"

    # 6c — amount equals fare (42.50)
    AMOUNT=$(echo "$SEARCH" | python3 -c "
import sys,json
d=json.load(sys.stdin)
payments=[p for p in d if p.get('rideId')==${SAGA_RIDE_ID}]
print(payments[0]['amount'] if payments else 'none')" 2>/dev/null)
    [ "$AMOUNT" = "42.5" ] || [ "$AMOUNT" = "42.50" ] \
      && report "(6c) PENDING payment amount=fare (42.50)" 1 \
      || report "(6c) PENDING payment amount=fare (42.50)" 0 "amount='$AMOUNT'"
  else
    for t in 6b 6c; do report "($t) saga forward payment" 0 "skipped — no JWT"; done
  fi
else
  for t in 6a 6b 6c; do report "($t) saga forward path" 0 "RabbitMQ unavailable"; done
fi

# ── 7. Idempotency: duplicate ride.completed (saga-events.md §idempotency) ───
echo; echo "=== Idempotency: duplicate ride.completed ==="
if [ "$RABBIT_UP" = "1" ] && [ -n "$TOKEN" ]; then
  RC_PAYLOAD=$(json_ride_completed "$SAGA_RIDE_ID" "$SAGA_USER_ID" "42.50")
  publish_to_ride_events "ride.completed" "$RC_PAYLOAD"
  sleep "$WAIT_SEC"

  COUNT=$(curl -s \
    "$PAYMENT_SVC/api/payments/search?status=PENDING&userId=${SAGA_USER_ID}" \
    -H "Authorization: Bearer $TOKEN" \
    | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(sum(1 for p in d if p.get('rideId')==${SAGA_RIDE_ID}))" 2>/dev/null)
  [ "$COUNT" = "1" ] && report "(7a) duplicate ride.completed → still 1 PENDING (idempotent)" 1 \
                      || report "(7a) duplicate ride.completed → still 1 PENDING (idempotent)" 0 "count=$COUNT"
else
  report "(7a) duplicate ride.completed idempotency" 0 "RabbitMQ or JWT unavailable"
fi

# ── 8. Saga: ride.cancelled → REFUNDED payment + payment.refunded ─────────────
# Spec: saga-events.md compensation path; uber-m3.md §7-EVENTS ride.cancelled consumer
echo; echo "=== Saga compensation path: ride.cancelled ==="
if [ "$RABBIT_UP" = "1" ]; then
  # Set up a capture queue for payment.refunded BEFORE publishing
  REFUND_CAPTURE_Q="test.capture.refunded.${SHORT_ID}"
  rabbit -X PUT "$RABBIT_API/api/queues/$VHOST/$REFUND_CAPTURE_Q" \
    -H "Content-Type: application/json" -d '{"durable":false,"auto_delete":true}' > /dev/null 2>&1
  rabbit -X POST "$RABBIT_API/api/bindings/$VHOST/e/payment.events/q/$REFUND_CAPTURE_Q" \
    -H "Content-Type: application/json" -d '{"routing_key":"payment.refunded"}' > /dev/null 2>&1

  CANCEL_PAYLOAD=$(json_ride_cancelled "$SAGA_RIDE_ID" "$SAGA_USER_ID" "payment_failed")
  publish_to_ride_events "ride.cancelled" "$CANCEL_PAYLOAD"
  sleep "$WAIT_SEC"

  # 8a — payment.refunded published to payment.events
  REFUNDED_COUNT=$(rabbit "$RABBIT_API/api/queues/$VHOST/$REFUND_CAPTURE_Q" \
    | python3 -c "import sys,json;print(json.load(sys.stdin).get('messages',0))" 2>/dev/null)
  [ "$REFUNDED_COUNT" -ge 1 ] 2>/dev/null \
    && report "(8a) ride.cancelled → payment.refunded published" 1 \
    || report "(8a) ride.cancelled → payment.refunded published" 0 "messages=$REFUNDED_COUNT"

  rabbit -X DELETE "$RABBIT_API/api/queues/$VHOST/$REFUND_CAPTURE_Q" > /dev/null 2>&1

  if [ -n "$TOKEN" ]; then
    # 8b — payment status is REFUNDED
    REFUND_SEARCH=$(curl -s \
      "$PAYMENT_SVC/api/payments/search?status=REFUNDED&userId=${SAGA_USER_ID}" \
      -H "Authorization: Bearer $TOKEN")
    FOUND_REF=$(echo "$REFUND_SEARCH" | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('True' if any(p.get('rideId')==${SAGA_RIDE_ID} for p in d) else 'False')" 2>/dev/null)
    [ "$FOUND_REF" = "True" ] && report "(8b) ride.cancelled → payment REFUNDED" 1 \
                               || report "(8b) ride.cancelled → payment REFUNDED" 0 "found=$FOUND_REF"

    # 8c — no PENDING payment remains for this rideId
    PENDING_CT=$(curl -s \
      "$PAYMENT_SVC/api/payments/search?status=PENDING&userId=${SAGA_USER_ID}" \
      -H "Authorization: Bearer $TOKEN" \
      | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(sum(1 for p in d if p.get('rideId')==${SAGA_RIDE_ID}))" 2>/dev/null)
    [ "$PENDING_CT" = "0" ] && report "(8c) no PENDING payment remains after cancellation" 1 \
                             || report "(8c) no PENDING payment remains after cancellation" 0 "pending=$PENDING_CT"
  else
    for t in 8b 8c; do report "($t) saga compensation" 0 "skipped — no JWT"; done
  fi
else
  for t in 8a 8b 8c; do report "($t) saga compensation path" 0 "RabbitMQ unavailable"; done
fi

# ── 9. Idempotency: duplicate ride.cancelled (saga-events.md §idempotency) ───
echo; echo "=== Idempotency: duplicate ride.cancelled ==="
if [ "$RABBIT_UP" = "1" ] && [ -n "$TOKEN" ]; then
  CANCEL_PAYLOAD=$(json_ride_cancelled "$SAGA_RIDE_ID" "$SAGA_USER_ID" "payment_failed")
  publish_to_ride_events "ride.cancelled" "$CANCEL_PAYLOAD"
  sleep "$WAIT_SEC"

  REFUND_CT=$(curl -s \
    "$PAYMENT_SVC/api/payments/search?status=REFUNDED&userId=${SAGA_USER_ID}" \
    -H "Authorization: Bearer $TOKEN" \
    | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(sum(1 for p in d if p.get('rideId')==${SAGA_RIDE_ID}))" 2>/dev/null)
  [ "$REFUND_CT" = "1" ] && report "(9a) duplicate ride.cancelled → still 1 REFUNDED (idempotent)" 1 \
                          || report "(9a) duplicate ride.cancelled → still 1 REFUNDED (idempotent)" 0 "count=$REFUND_CT"
else
  report "(9a) duplicate ride.cancelled idempotency" 0 "RabbitMQ or JWT unavailable"
fi

# ── 10. Boundary: ride.cancelled with no pre-existing payment ────────────────
# Spec: saga-events.md — "No refundable payment... silently skip"
echo; echo "=== Boundary: ride.cancelled with no prior payment ==="
if [ "$RABBIT_UP" = "1" ] && [ -n "$TOKEN" ]; then
  ORPHAN_PAYLOAD=$(json_ride_cancelled "$ORPHAN_RIDE_ID" "$SAGA_USER_ID" "user_requested")
  publish_to_ride_events "ride.cancelled" "$ORPHAN_PAYLOAD"
  sleep "$WAIT_SEC"

  ORPHAN_CT=$(curl -s "$PAYMENT_SVC/api/payments/search?userId=${SAGA_USER_ID}" \
    -H "Authorization: Bearer $TOKEN" \
    | python3 -c "
import sys,json
d=json.load(sys.stdin)
print(sum(1 for p in d if p.get('rideId')==${ORPHAN_RIDE_ID}))" 2>/dev/null)
  [ "$ORPHAN_CT" = "0" ] && report "(10a) ride.cancelled no prior payment → silently skipped" 1 \
                          || report "(10a) ride.cancelled no prior payment → silently skipped" 0 "count=$ORPHAN_CT"
else
  report "(10a) ride.cancelled no prior payment" 0 "RabbitMQ or JWT unavailable"
fi

# ── 11. Boundary: ride.completed with fare=null ───────────────────────────────
# Spec: uber-m3.md §7-EVENTS — null fare → amount=0.0
echo; echo "=== Boundary: ride.completed with fare=null ==="
if [ "$RABBIT_UP" = "1" ] && [ -n "$TOKEN" ]; then
  NULL_PAYLOAD=$(python3 -c "import json,sys
print(json.dumps({'rideId':${NULL_FARE_RIDE_ID},'userId':${SAGA_USER_ID},'driverId':5,'fare':None}))")
  publish_to_ride_events "ride.completed" "$NULL_PAYLOAD"
  sleep "$WAIT_SEC"

  NULL_AMOUNT=$(curl -s \
    "$PAYMENT_SVC/api/payments/search?status=PENDING&userId=${SAGA_USER_ID}" \
    -H "Authorization: Bearer $TOKEN" \
    | python3 -c "
import sys,json
d=json.load(sys.stdin)
payments=[p for p in d if p.get('rideId')==${NULL_FARE_RIDE_ID}]
print(payments[0]['amount'] if payments else 'none')" 2>/dev/null)
  [ "$NULL_AMOUNT" = "0" ] || [ "$NULL_AMOUNT" = "0.0" ] \
    && report "(11a) ride.completed fare=null → payment amount=0.0" 1 \
    || report "(11a) ride.completed fare=null → payment amount=0.0" 0 "amount='$NULL_AMOUNT'"
else
  report "(11a) ride.completed fare=null" 0 "RabbitMQ or JWT unavailable"
fi

# (no idempotency cases for health — it is read-only and stateless)

# ── Totals ────────────────────────────────────────────────────────────────────
echo
echo "TOTALS: $PASS PASS / $FAIL FAIL"
exit "$FAIL"
