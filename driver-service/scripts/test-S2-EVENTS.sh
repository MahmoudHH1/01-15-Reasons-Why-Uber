#!/usr/bin/env bash
# End-to-end tests for S2-EVENTS — driver-service RabbitMQ publishers + consumers.
#
# Spec coverage:
#   §2.5-§2.11   RabbitMQ topology (TopicExchange, DLQ, auto ACK, publish-after-commit)
#   §4 S2-F4/F7  publish hooks (driver.status-changed, driver.rated)
#   §4 S2-F8     publish hook (driver.document.verified) — partial (admin bootstrap heavy)
#   §8 / saga    driver-side ride.placed / ride.completed / ride.cancelled consumers
#   §13.2        S2-EVENTS deliverable acceptance criteria
#   §16#11       state-guarded idempotency for at-least-once delivery
#
# Prereqs (local docker-compose):
#   docker compose up -d postgres driver-postgres mongo redis elasticsearch \
#                       rabbitmq user-service driver-service ride-service
#
# Exit code = number of FAIL assertions. 0 = all green.

set -u

USER_SVC="${USER_SVC:-http://localhost:8081}"
DRIVER_SVC="${DRIVER_SVC:-http://localhost:8082}"
RIDE_SVC="${RIDE_SVC:-http://localhost:8083}"
RABBIT_HOST="${RABBIT_HOST:-localhost}"
RABBIT_MGMT_PORT="${RABBIT_MGMT_PORT:-15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
RABBIT_VHOST="${RABBIT_VHOST:-%2F}"
MONGO_USER="${MONGO_USER:-root}"
MONGO_PASS="${MONGO_PASS:-rootpass}"
MONGO_DB="${MONGO_DB:-ubermongo}"
RIDE_DB_CONTAINER="${RIDE_DB_CONTAINER:-uber-db}"
RIDE_DB_NAME="${RIDE_DB_NAME:-uberdb}"

RUN_ID="$(date +%s)$$"
TAP_QUEUE="test.driver.events.tap.$RUN_ID"

PASS=0
FAIL=0
report() {
  local name="$1" cond="$2" detail="${3:-}"
  if [ "$cond" = "1" ]; then PASS=$((PASS+1)); echo "PASS  $name"
  else                       FAIL=$((FAIL+1)); echo "FAIL  $name -- $detail"
  fi
}

rmq() {
  curl -s -u "$RABBIT_USER:$RABBIT_PASS" -H 'Content-Type: application/json' "$@"
}

mongo_eval() {
  docker exec uber-mongo mongosh --quiet -u "$MONGO_USER" -p "$MONGO_PASS" \
    --authenticationDatabase admin "$MONGO_DB" --eval "$1" 2>&1
}

pg_eval() {
  local container="$1" sql="$2"
  docker exec "$container" psql -U postgres -d "$RIDE_DB_NAME" -t -A -c "$sql" 2>&1
}

publish_event() {
  python3 -c "
import json,sys
envelope = {
  'properties': {
    'content_type': 'application/json',
    'headers': {'__TypeId__': sys.argv[2]}
  },
  'routing_key': sys.argv[1],
  'payload': sys.argv[3],
  'payload_encoding': 'string'
}
print(json.dumps(envelope))
" "$1" "$2" "$3" | rmq -X POST "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/exchanges/$RABBIT_VHOST/ride.events/publish" --data-binary @-
}

drain_tap() {
  rmq -X POST "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/queues/$RABBIT_VHOST/$TAP_QUEUE/get" \
    -d '{"count":100,"ackmode":"ack_requeue_false","encoding":"auto"}'
}

contains_routing_key() {
  local msgs="$1" key="$2"
  echo "$msgs" | python3 -c "import sys,json; msgs=json.load(sys.stdin); print('1' if any(m.get('routing_key')==sys.argv[1] for m in msgs) else '0')" "$key" 2>/dev/null
}

payload_for_routing_key() {
  local msgs="$1" key="$2"
  echo "$msgs" | python3 -c "import sys,json; msgs=json.load(sys.stdin); print(next((m.get('payload','') for m in msgs if m.get('routing_key')==sys.argv[1]), ''))" "$key" 2>/dev/null
}

# ── 0. health roll-up ──────────────────────────────────────────────────
echo "=== Health checks ==="
USER_HC=$(curl -s -o /dev/null -w '%{http_code}' "$USER_SVC/api/users/health")
DRIVER_HC=$(curl -s -o /dev/null -w '%{http_code}' "$DRIVER_SVC/api/drivers/health")
RIDE_HC=$(curl -s -o /dev/null -w '%{http_code}' "$RIDE_SVC/api/rides/health")
RMQ_HC=$(curl -s -o /dev/null -w '%{http_code}' -u "$RABBIT_USER:$RABBIT_PASS" "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/overview")

[ "$USER_HC" = "200" ]    && report "(0a) user-service /health"   1 || report "(0a) user-service /health"   0 "got $USER_HC"
[ "$DRIVER_HC" = "200" ]  && report "(0b) driver-service /health" 1 || report "(0b) driver-service /health" 0 "got $DRIVER_HC"
[ "$RIDE_HC" = "200" ]    && report "(0c) ride-service /health"   1 || report "(0c) ride-service /health"   0 "got $RIDE_HC"
[ "$RMQ_HC" = "200" ]     && report "(0d) rabbitmq /api/overview" 1 || report "(0d) rabbitmq /api/overview" 0 "got $RMQ_HC"
[ "$FAIL" -gt 0 ] && { echo "stack not healthy — aborting"; exit "$FAIL"; }

# ── 1. setup tap queue on driver.events exchange ───────────────────────
echo
echo "=== Setup tap queue $TAP_QUEUE bound to driver.events ==="
rmq -X PUT "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/queues/$RABBIT_VHOST/$TAP_QUEUE" \
  -d '{"auto_delete":false,"durable":false,"arguments":{}}' >/dev/null
TAP_BIND=$(rmq -X POST "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/bindings/$RABBIT_VHOST/e/driver.events/q/$TAP_QUEUE" \
  -d '{"routing_key":"#"}' -o /dev/null -w '%{http_code}')
[ "$TAP_BIND" = "201" ] && report "(1) tap queue bound to driver.events#" 1 || report "(1) tap queue bound" 0 "POST /api/bindings got $TAP_BIND"

# Also verify DriverEventConfig declared the topology
EXCH=$(rmq "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/exchanges/$RABBIT_VHOST/driver.events" -o /dev/null -w '%{http_code}')
[ "$EXCH" = "200" ] && report "(2) driver.events TopicExchange exists" 1 || report "(2) driver.events exists" 0 "got $EXCH"
QUEUE=$(rmq "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/queues/$RABBIT_VHOST/driver.ride.saga-listener" -o /dev/null -w '%{http_code}')
[ "$QUEUE" = "200" ] && report "(3) driver.ride.saga-listener queue exists" 1 || report "(3) saga-listener queue exists" 0 "got $QUEUE"
DLQ=$(rmq "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/queues/$RABBIT_VHOST/driver.ride.saga-listener.dlq" -o /dev/null -w '%{http_code}')
[ "$DLQ" = "200" ] && report "(4) driver.ride.saga-listener.dlq exists" 1 || report "(4) DLQ exists" 0 "got $DLQ"

# ── 5. register a test user + grab JWT ─────────────────────────────────
echo
echo "=== Register test user + obtain JWT ==="
TEST_EMAIL="s2-events-${RUN_ID}@uber.local"
TEST_PHONE="020${RUN_ID: -8}"
REG_RESP=$(curl -s -X POST "$USER_SVC/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"S2 Tester\",\"email\":\"$TEST_EMAIL\",\"password\":\"Pass123!\",\"phone\":\"$TEST_PHONE\"}")
TOKEN=$(echo "$REG_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
[ -n "$TOKEN" ] && report "(5) JWT acquired" 1 || { report "(5) JWT acquired" 0 "$REG_RESP"; exit "$FAIL"; }
USER_ID=$(echo "$REG_RESP" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('user',{}).get('id') or d.get('id') or '')" 2>/dev/null)

# ── 6. create a test driver (initially AVAILABLE) ──────────────────────
echo
echo "=== Create test driver ==="
mongo_eval 'db.driver_events.deleteMany({})' >/dev/null
DRIVER_RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"name\":\"S2EVENTS\",\"email\":\"d-${RUN_ID}@s2events.test\",\"phone\":\"030${RUN_ID: -8}\",\"licenseNumber\":\"LIC-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SEDAN\"}}")
DRIVER_ID=$(echo "$DRIVER_RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
[ -n "$DRIVER_ID" ] && report "(6) driver created id=$DRIVER_ID" 1 || { report "(6) driver create" 0 "$DRIVER_RESP"; exit "$FAIL"; }

# drain any background events from driver creation
sleep 0.5
drain_tap >/dev/null

# ── 7. S2-F4: PUT availability → publishes driver.status-changed ───────
echo
echo "=== S2-F4: update availability publishes driver.status-changed ==="
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/availability" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"OFFLINE"}')
[ "$CODE" = "200" ] && report "(F4-1) PUT availability=OFFLINE returns 200" 1 || report "(F4-1) PUT availability 200" 0 "got $CODE"

sleep 0.6
TAP_MSG=$(drain_tap)
[ "$(contains_routing_key "$TAP_MSG" driver.status-changed)" = "1" ] \
  && report "(F4-2) driver.status-changed published to driver.events" 1 \
  || report "(F4-2) driver.status-changed published" 0 "no message with routing_key=driver.status-changed"

STATUS_PAYLOAD=$(payload_for_routing_key "$TAP_MSG" driver.status-changed)
echo "$STATUS_PAYLOAD" | grep -q "\"driverId\":$DRIVER_ID" && report "(F4-3) payload carries driverId=$DRIVER_ID" 1 || report "(F4-3) payload driverId" 0 "$STATUS_PAYLOAD"
echo "$STATUS_PAYLOAD" | grep -q "\"newStatus\":\"OFFLINE\"" && report "(F4-4) payload newStatus=OFFLINE" 1 || report "(F4-4) payload newStatus" 0 "$STATUS_PAYLOAD"
echo "$STATUS_PAYLOAD" | grep -q "\"oldStatus\":\"AVAILABLE\"" && report "(F4-5) payload oldStatus=AVAILABLE" 1 || report "(F4-5) payload oldStatus" 0 "$STATUS_PAYLOAD"

MONGO_AUD=$(mongo_eval "db.driver_events.countDocuments({action:'AVAILABILITY_UPDATED','params.driverId':$DRIVER_ID})" | tail -1 | tr -d '\r')
[ "$MONGO_AUD" = "1" ] && report "(F4-6) Mongo AVAILABILITY_UPDATED audit written" 1 || report "(F4-6) Mongo audit" 0 "count=$MONGO_AUD"

# ── 8. F4 boundary: invalid status → 400 ──────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/availability" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"PHANTOM"}')
[ "$CODE" = "400" ] && report "(F4-7) invalid status → 400" 1 || report "(F4-7) invalid status → 400" 0 "got $CODE"

# ── 9. F4 auth: missing token → 401 ───────────────────────────────────
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/availability" \
  -H 'Content-Type: application/json' -d '{"status":"AVAILABLE"}')
[ "$CODE" = "401" ] && report "(F4-8) missing token → 401" 1 || report "(F4-8) missing token → 401" 0 "got $CODE"

# reset driver to AVAILABLE for downstream consumer tests
curl -s -o /dev/null -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_ID/availability" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"AVAILABLE"}'
sleep 0.6
drain_tap >/dev/null

# ── 10. Consumer: ride.placed → driver becomes BUSY ────────────────────
echo
echo "=== Consumer: ride.placed → BUSY (state-guard L758) ==="
RIDE_ID=$((RANDOM * 1000 + 1))
publish_event "ride.placed" "com.team01.uber.contracts.events.RidePlacedEvent" \
  "{\"rideId\":$RIDE_ID,\"userId\":1,\"driverId\":$DRIVER_ID}" >/dev/null
sleep 1.5
DRIVER_AFTER=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN")
STATUS=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
[ "$STATUS" = "BUSY" ] && report "(C-1) ride.placed flipped driver to BUSY" 1 || report "(C-1) ride.placed → BUSY" 0 "status=$STATUS"

# ── 11. Idempotency: duplicate ride.placed → still BUSY ───────────────
publish_event "ride.placed" "com.team01.uber.contracts.events.RidePlacedEvent" \
  "{\"rideId\":$RIDE_ID,\"userId\":1,\"driverId\":$DRIVER_ID}" >/dev/null
sleep 1
STATUS=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
[ "$STATUS" = "BUSY" ] && report "(C-2) duplicate ride.placed: still BUSY (idempotent no-op)" 1 \
                       || report "(C-2) duplicate ride.placed: still BUSY" 0 "status=$STATUS"

# ── 12. Consumer: ride.completed → AVAILABLE + earnings ───────────────
echo
echo "=== Consumer: ride.completed → AVAILABLE + earnings ==="
FARE=25.50
publish_event "ride.completed" "com.team01.uber.contracts.events.RideCompletedEvent" \
  "{\"rideId\":$RIDE_ID,\"userId\":1,\"driverId\":$DRIVER_ID,\"fare\":$FARE}" >/dev/null
sleep 1.5
DRIVER_AFTER=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN")
STATUS=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
TCR=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalCompletedRides',0))" 2>/dev/null)
TEARN=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalEarnings',0))" 2>/dev/null)
[ "$STATUS" = "AVAILABLE" ] && report "(C-3) ride.completed → AVAILABLE" 1 || report "(C-3) ride.completed → AVAILABLE" 0 "status=$STATUS"
[ "$TCR" = "1" ] && report "(C-4) totalCompletedRides incremented to 1" 1 || report "(C-4) totalCompletedRides=1" 0 "tcr=$TCR"
python3 -c "import sys;sys.exit(0 if abs(float(sys.argv[1])-25.5) < 0.01 else 1)" "$TEARN" \
  && report "(C-5) totalEarnings incremented to 25.5" 1 \
  || report "(C-5) totalEarnings=25.5" 0 "earnings=$TEARN"

# ── 13. Idempotency: duplicate ride.completed → no double-increment ────
publish_event "ride.completed" "com.team01.uber.contracts.events.RideCompletedEvent" \
  "{\"rideId\":$RIDE_ID,\"userId\":1,\"driverId\":$DRIVER_ID,\"fare\":$FARE}" >/dev/null
sleep 1.5
TCR2=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalCompletedRides',0))" 2>/dev/null)
[ "$TCR2" = "1" ] && report "(C-6) duplicate ride.completed: tcr still 1" 1 \
                  || report "(C-6) duplicate ride.completed idempotency" 0 "tcr=$TCR2"

# ── 14. ride.cancelled with driverId=null → silently ignored (L1335) ───
echo
echo "=== Consumer: ride.cancelled with null driverId → silent ignore ==="
TEARN_BEFORE="$TEARN"
publish_event "ride.cancelled" "com.team01.uber.contracts.events.RideCancelledEvent" \
  "{\"rideId\":$((RIDE_ID + 1)),\"userId\":1,\"driverId\":null,\"reason\":\"user_requested\"}" >/dev/null
sleep 1
DRIVER_AFTER=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN")
TEARN_AFTER=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalEarnings',0))" 2>/dev/null)
[ "$TEARN_AFTER" = "$TEARN_BEFORE" ] && report "(C-7) ride.cancelled driverId=null: no state change" 1 \
                                     || report "(C-7) ride.cancelled null driverId" 0 "before=$TEARN_BEFORE after=$TEARN_AFTER"

# ── 15. ride.cancelled with status=BUSY → AVAILABLE (no stats change) ──
echo
echo "=== Consumer: ride.cancelled status=BUSY → AVAILABLE ==="
RIDE_ID2=$((RANDOM * 1000 + 2))
# flip to BUSY again via another ride.placed
publish_event "ride.placed" "com.team01.uber.contracts.events.RidePlacedEvent" \
  "{\"rideId\":$RIDE_ID2,\"userId\":1,\"driverId\":$DRIVER_ID}" >/dev/null
sleep 1.5
STATUS=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
[ "$STATUS" = "BUSY" ] && report "(C-8a) re-flipped to BUSY for ride.cancelled test" 1 || report "(C-8a) re-flip BUSY" 0 "status=$STATUS"

TCR_BEFORE=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalCompletedRides',0))" 2>/dev/null)
publish_event "ride.cancelled" "com.team01.uber.contracts.events.RideCancelledEvent" \
  "{\"rideId\":$RIDE_ID2,\"userId\":1,\"driverId\":$DRIVER_ID,\"reason\":\"user_requested\"}" >/dev/null
sleep 1.5
DRIVER_AFTER=$(curl -s "$DRIVER_SVC/api/drivers/$DRIVER_ID" -H "Authorization: Bearer $TOKEN")
STATUS=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
TCR_AFTER=$(echo "$DRIVER_AFTER" | python3 -c "import sys,json;print(json.load(sys.stdin).get('totalCompletedRides',0))" 2>/dev/null)
[ "$STATUS" = "AVAILABLE" ] && report "(C-8b) ride.cancelled (was BUSY) → AVAILABLE" 1 \
                            || report "(C-8b) ride.cancelled status=BUSY → AVAILABLE" 0 "status=$STATUS"
[ "$TCR_AFTER" = "$TCR_BEFORE" ] && report "(C-8c) tcr unchanged when reversing from BUSY" 1 \
                                || report "(C-8c) tcr unchanged when reversing from BUSY" 0 "before=$TCR_BEFORE after=$TCR_AFTER"

# ── 16. S2-F7 rating bounds: rating > 5 → 400 ──────────────────────────
echo
echo "=== S2-F7: rate a driver publishes driver.rated ==="
# Need a COMPLETED ride in ride-service DB. Attempt direct INSERT (best-effort).
RIDE_ROW_ID=$((RANDOM * 10000 + 999))
INSERT_OUT=$(pg_eval "$RIDE_DB_CONTAINER" "INSERT INTO rides (id, user_id, driver_id, status, fare, created_at) VALUES ($RIDE_ROW_ID, 1, $DRIVER_ID, 'COMPLETED', 50.0, NOW()) ON CONFLICT (id) DO NOTHING")
if echo "$INSERT_OUT" | grep -q "INSERT\|^$"; then
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DRIVER_SVC/api/drivers/$DRIVER_ID/rate" \
    -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 1" -H 'Content-Type: application/json' \
    -d "{\"rideId\":$RIDE_ROW_ID,\"rating\":7}")
  [ "$CODE" = "400" ] && report "(F7-1) rating>5 → 400" 1 || report "(F7-1) rating>5 → 400" 0 "got $CODE"

  drain_tap >/dev/null
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DRIVER_SVC/api/drivers/$DRIVER_ID/rate" \
    -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 1" -H 'Content-Type: application/json' \
    -d "{\"rideId\":$RIDE_ROW_ID,\"rating\":5}")
  [ "$CODE" = "200" ] && report "(F7-2) POST /rate returns 200" 1 || report "(F7-2) POST /rate 200" 0 "got $CODE"

  sleep 0.6
  TAP_MSG=$(drain_tap)
  [ "$(contains_routing_key "$TAP_MSG" driver.rated)" = "1" ] \
    && report "(F7-3) driver.rated published" 1 \
    || report "(F7-3) driver.rated published" 0 "no message with routing_key=driver.rated"

  RATED_PAYLOAD=$(payload_for_routing_key "$TAP_MSG" driver.rated)
  echo "$RATED_PAYLOAD" | grep -q "\"rideId\":$RIDE_ROW_ID" && report "(F7-4) payload rideId=$RIDE_ROW_ID" 1 || report "(F7-4) payload rideId" 0 "$RATED_PAYLOAD"
  echo "$RATED_PAYLOAD" | grep -q "\"rating\":5" && report "(F7-5) payload rating=5.0" 1 || report "(F7-5) payload rating=5" 0 "$RATED_PAYLOAD"
  echo "$RATED_PAYLOAD" | grep -q "\"driverId\":$DRIVER_ID" && report "(F7-6) payload driverId=$DRIVER_ID" 1 || report "(F7-6) payload driverId" 0 "$RATED_PAYLOAD"

  pg_eval "$RIDE_DB_CONTAINER" "DELETE FROM rides WHERE id=$RIDE_ROW_ID" >/dev/null 2>&1 || true
else
  echo "WARN  could not seed COMPLETED ride in $RIDE_DB_CONTAINER:$RIDE_DB_NAME — skipping F7 tests"
  echo "  INSERT_OUT: $INSERT_OUT"
fi

# ── 17. Cleanup ───────────────────────────────────────────────────────
rmq -X DELETE "http://$RABBIT_HOST:$RABBIT_MGMT_PORT/api/queues/$RABBIT_VHOST/$TAP_QUEUE" >/dev/null

echo
echo "TOTALS: $PASS PASS / $FAIL FAIL"
exit "$FAIL"
