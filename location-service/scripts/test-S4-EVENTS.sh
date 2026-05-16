#!/usr/bin/env bash
# End-to-end test for S4-EVENTS (location-service RabbitMQ topology + saga consumers + K8s actuator).
# Exercises: location.tracked publisher, ride.placed/completed/cancelled consumers,
# S4-F3 Feign nearby-drivers, S4-F9 Feign stationary-drivers, actuator endpoints.
#
# Prerequisites:
#   Full stack running (docker-compose or MiniKube):
#   location-service, driver-service, RabbitMQ, Cassandra, MongoDB, PostgreSQL.
#
# Exit code: number of FAILing assertions. 0 = all green.

set -u

GATEWAY_URL="${GATEWAY_URL:-http://localhost:30080}"
LOCATION_SVC="${LOCATION_SVC:-http://localhost:8084}"
RABBIT_HOST="${RABBIT_HOST:-localhost}"
RABBIT_MGT_PORT="${RABBIT_MGT_PORT:-15672}"
RABBIT_USER="${RABBIT_USER:-guest}"
RABBIT_PASS="${RABBIT_PASS:-guest}"
MONGO_USER="${MONGO_USER:-root}"
MONGO_PASS="${MONGO_PASS:-rootpass}"
MONGO_DB="${MONGO_DB:-ubermongo}"
CASSANDRA_HOST="${CASSANDRA_HOST:-localhost}"

RUN_ID="$(date +%s)$$"
TEST_DRIVER_ID=9901
TEST_RIDE_ID=8801
TEST_USER_ID=7701

PASS=0
FAIL=0

report() {
  local name="$1" cond="$2" detail="${3:-}"
  if [ "$cond" = "1" ]; then PASS=$((PASS+1)); echo "PASS  $name"
  else                       FAIL=$((FAIL+1)); echo "FAIL  $name -- $detail"
  fi
}

mongo_eval() {
  docker exec uber-mongo mongosh --quiet \
    -u "$MONGO_USER" -p "$MONGO_PASS" \
    --authenticationDatabase admin "$MONGO_DB" \
    --eval "$1" 2>&1
}

rabbit_publish() {
  local queue="$1" routing_key="$2" payload="$3"
  curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
    -H "Content-Type: application/json" \
    -d "{\"properties\":{\"headers\":{\"__TypeId__\":\"${routing_key}\"}},\"routing_key\":\"${routing_key}\",\"payload\":${payload},\"payload_encoding\":\"string\"}" \
    "http://${RABBIT_HOST}:${RABBIT_MGT_PORT}/api/exchanges/%2F/ride.events/publish" \
    > /dev/null
}

rabbit_queue_count() {
  curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
    "http://${RABBIT_HOST}:${RABBIT_MGT_PORT}/api/queues/%2F/$1" \
    | grep -o '"messages":[0-9]*' | head -1 | cut -d: -f2
}

jwt_token() {
  curl -s -X POST "$GATEWAY_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"email":"admin@uber.local","password":"Admin123!"}' \
    | grep -o '"token":"[^"]*"' | cut -d'"' -f4
}

# ── 0. Cleanup ──────────────────────────────────────────────────────────────
echo "=== 0. Cleanup ==="
mongo_eval "db.location_events.deleteMany({\"payload.driverId\": $TEST_DRIVER_ID})" > /dev/null 2>&1 || true
echo "Cleanup done (test driverId=$TEST_DRIVER_ID)."

# ── 1. Health ────────────────────────────────────────────────────────────────
echo ""
echo "=== 1. Health ==="
LOC_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$LOCATION_SVC/api/locations/health")
[ "$LOC_HEALTH" = "200" ] && report "(1a) location-service /health" 1 || report "(1a) location-service /health" 0 "got $LOC_HEALTH"

# ── 2. Actuator ──────────────────────────────────────────────────────────────
echo ""
echo "=== 2. Actuator ==="
ACT_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$LOCATION_SVC/actuator/health")
[ "$ACT_HEALTH" = "200" ] && report "(2a) /actuator/health exposed" 1 || report "(2a) /actuator/health exposed" 0 "got $ACT_HEALTH"

ACT_PROM=$(curl -s -o /dev/null -w "%{http_code}" "$LOCATION_SVC/actuator/prometheus")
[ "$ACT_PROM" = "200" ] && report "(2b) /actuator/prometheus exposed" 1 || report "(2b) /actuator/prometheus exposed" 0 "got $ACT_PROM"

PROM_BODY=$(curl -s "$LOCATION_SVC/actuator/prometheus")
echo "$PROM_BODY" | grep -q "http_server_requests_seconds" \
  && report "(2c) prometheus metrics contain http_server_requests_seconds" 1 \
  || report "(2c) prometheus metrics contain http_server_requests_seconds" 0 "metric missing"

# ── 3. Auth guard ────────────────────────────────────────────────────────────
echo ""
echo "=== 3. Auth guard ==="
NO_TOKEN=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY_URL/api/locations")
[ "$NO_TOKEN" = "401" ] || [ "$NO_TOKEN" = "403" ] \
  && report "(3a) GET /api/locations rejected without JWT" 1 \
  || report "(3a) GET /api/locations rejected without JWT" 0 "got $NO_TOKEN"

TOKEN=$(jwt_token)
[ -n "$TOKEN" ] && report "(3b) JWT obtained from /api/auth/login" 1 || report "(3b) JWT obtained from /api/auth/login" 0 "empty token"

# ── 4. location.ride.saga-listener queue exists ──────────────────────────────
echo ""
echo "=== 4. RabbitMQ topology ==="
QUEUE_INFO=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
  "http://${RABBIT_HOST}:${RABBIT_MGT_PORT}/api/queues/%2F/location.ride.saga-listener")
echo "$QUEUE_INFO" | grep -q '"durable":true' \
  && report "(4a) location.ride.saga-listener queue exists and is durable" 1 \
  || report "(4a) location.ride.saga-listener queue exists and is durable" 0 "queue missing or not durable"

DLQ_INFO=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
  "http://${RABBIT_HOST}:${RABBIT_MGT_PORT}/api/queues/%2F/location.ride.saga-listener.dlq")
echo "$DLQ_INFO" | grep -q '"durable":true' \
  && report "(4b) location.ride.saga-listener.dlq exists and is durable" 1 \
  || report "(4b) location.ride.saga-listener.dlq exists and is durable" 0 "DLQ missing"

EXCH_INFO=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
  "http://${RABBIT_HOST}:${RABBIT_MGT_PORT}/api/exchanges/%2F/location.events")
echo "$EXCH_INFO" | grep -q '"type":"topic"' \
  && report "(4c) location.events TopicExchange exists" 1 \
  || report "(4c) location.events TopicExchange exists" 0 "exchange missing or wrong type"

# ── 5. location.tracked publisher ────────────────────────────────────────────
echo ""
echo "=== 5. location.tracked publisher ==="
GPS_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$GATEWAY_URL/api/locations/$TEST_DRIVER_ID/tracking" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"latitude\":30.04,\"longitude\":31.23,\"speed\":0.0,\"heading\":0.0,\"accuracy\":5.0}")
[ "$GPS_STATUS" = "200" ] || [ "$GPS_STATUS" = "201" ] \
  && report "(5a) POST /api/locations/{driverId}/tracking returns 2xx" 1 \
  || report "(5a) POST /api/locations/{driverId}/tracking returns 2xx" 0 "got $GPS_STATUS"

sleep 1
BINDINGS=$(curl -s -u "${RABBIT_USER}:${RABBIT_PASS}" \
  "http://${RABBIT_HOST}:${RABBIT_MGT_PORT}/api/exchanges/%2F/location.events/bindings/source")
echo "$BINDINGS" | grep -q '"routing_key":"location.tracked"' \
  && report "(5b) location.events has location.tracked routing key binding" 1 \
  || report "(5b) location.events has location.tracked routing key binding" 0 "binding not found"

# ── 6. ride.completed consumer — mark final ping with rideId ─────────────────
echo ""
echo "=== 6. ride.completed consumer ==="
rabbit_publish "location.ride.saga-listener" "ride.completed" \
  "{\"rideId\":$TEST_RIDE_ID,\"userId\":$TEST_USER_ID,\"driverId\":$TEST_DRIVER_ID,\"fare\":42.5}"
sleep 2

CASSANDRA_RIDEID=$(docker exec uber-cassandra cqlsh "$CASSANDRA_HOST" -e \
  "SELECT ride_id FROM uberks.location_tracking_events WHERE driver_id=$TEST_DRIVER_ID LIMIT 1;" 2>/dev/null \
  | grep -v "^-\|ride_id\|rows\|Warning\|^$" | tr -d ' ' | head -1)
[ "$CASSANDRA_RIDEID" = "$TEST_RIDE_ID" ] \
  && report "(6a) ride.completed: latest Cassandra ping has rideId=$TEST_RIDE_ID" 1 \
  || report "(6a) ride.completed: latest Cassandra ping has rideId=$TEST_RIDE_ID" 0 "got '$CASSANDRA_RIDEID'"

MONGO_RIDE_LOG=$(mongo_eval "db.location_events.countDocuments({\"payload.driverId\":$TEST_DRIVER_ID,\"action\":\"TRACKING_RECORDED\"})")
[ "$MONGO_RIDE_LOG" -ge "1" ] 2>/dev/null \
  && report "(6b) ride.completed: Mongo TRACKING_RECORDED event logged" 1 \
  || report "(6b) ride.completed: Mongo TRACKING_RECORDED event logged" 0 "count=$MONGO_RIDE_LOG"

# ── 7. Idempotency — duplicate ride.completed ─────────────────────────────────
echo ""
echo "=== 7. Idempotency ==="
rabbit_publish "location.ride.saga-listener" "ride.completed" \
  "{\"rideId\":$TEST_RIDE_ID,\"userId\":$TEST_USER_ID,\"driverId\":$TEST_DRIVER_ID,\"fare\":42.5}"
sleep 2

CASSANDRA_RIDEID_2=$(docker exec uber-cassandra cqlsh "$CASSANDRA_HOST" -e \
  "SELECT ride_id FROM uberks.location_tracking_events WHERE driver_id=$TEST_DRIVER_ID LIMIT 1;" 2>/dev/null \
  | grep -v "^-\|ride_id\|rows\|Warning\|^$" | tr -d ' ' | head -1)
[ "$CASSANDRA_RIDEID_2" = "$TEST_RIDE_ID" ] \
  && report "(7a) idempotency: duplicate ride.completed does not corrupt rideId" 1 \
  || report "(7a) idempotency: duplicate ride.completed does not corrupt rideId" 0 "got '$CASSANDRA_RIDEID_2'"

# ── 8. ride.cancelled consumer — log TRIP_CANCELLED to Mongo ─────────────────
echo ""
echo "=== 8. ride.cancelled consumer ==="
rabbit_publish "location.ride.saga-listener" "ride.cancelled" \
  "{\"rideId\":$TEST_RIDE_ID,\"userId\":$TEST_USER_ID,\"driverId\":$TEST_DRIVER_ID,\"reason\":\"payment_failed\"}"
sleep 2

MONGO_CANCELLED=$(mongo_eval "db.location_events.countDocuments({\"payload.driverId\":$TEST_DRIVER_ID,\"action\":\"TRIP_CANCELLED\"})")
[ "$MONGO_CANCELLED" -ge "1" ] 2>/dev/null \
  && report "(8a) ride.cancelled: Mongo TRIP_CANCELLED event logged" 1 \
  || report "(8a) ride.cancelled: Mongo TRIP_CANCELLED event logged" 0 "count=$MONGO_CANCELLED"

# ── 9. ride.placed consumer — acknowledged ────────────────────────────────────
echo ""
echo "=== 9. ride.placed consumer ==="
rabbit_publish "location.ride.saga-listener" "ride.placed" \
  "{\"rideId\":$TEST_RIDE_ID,\"userId\":$TEST_USER_ID,\"driverId\":$TEST_DRIVER_ID}"
sleep 2

DLQ_AFTER_PLACED=$(rabbit_queue_count "location.ride.saga-listener.dlq")
[ "${DLQ_AFTER_PLACED:-0}" = "0" ] \
  && report "(9a) ride.placed processed without routing to DLQ" 1 \
  || report "(9a) ride.placed processed without routing to DLQ" 0 "DLQ count=$DLQ_AFTER_PLACED"

# ── 10. S4-F3 nearby-drivers (Feign) ─────────────────────────────────────────
echo ""
echo "=== 10. S4-F3 nearby-drivers ==="
NEARBY=$(curl -s -o /dev/null -w "%{http_code}" \
  "$GATEWAY_URL/api/locations/nearby?lat=30.0&lon=31.0&radiusKm=50" \
  -H "Authorization: Bearer $TOKEN")
[ "$NEARBY" = "200" ] \
  && report "(10a) GET /api/locations/nearby returns 200" 1 \
  || report "(10a) GET /api/locations/nearby returns 200" 0 "got $NEARBY"

# Verify only AVAILABLE drivers are returned (spot check: no BUSY drivers in result)
NEARBY_BODY=$(curl -s "$GATEWAY_URL/api/locations/nearby?lat=30.0&lon=31.0&radiusKm=50" \
  -H "Authorization: Bearer $TOKEN")
echo "$NEARBY_BODY" | grep -q '"status":"BUSY"' \
  && report "(10b) S4-F3: result must NOT contain BUSY drivers" 0 "found BUSY driver in response" \
  || report "(10b) S4-F3: result does not contain BUSY drivers" 1

# ── 11. S4-F9 stationary-drivers (Feign) ─────────────────────────────────────
echo ""
echo "=== 11. S4-F9 stationary-drivers ==="
STATIONARY=$(curl -s -o /dev/null -w "%{http_code}" \
  "$GATEWAY_URL/api/locations/stationary?maxSpeed=5.0&sinceMinutes=60" \
  -H "Authorization: Bearer $TOKEN")
[ "$STATIONARY" = "200" ] \
  && report "(11a) GET /api/locations/stationary returns 200" 1 \
  || report "(11a) GET /api/locations/stationary returns 200" 0 "got $STATIONARY"

# ── 12. Error cases ───────────────────────────────────────────────────────────
echo ""
echo "=== 12. Error cases ==="
INVALID_GPS=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$GATEWAY_URL/api/locations/$TEST_DRIVER_ID/tracking" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"latitude":999.0,"longitude":31.23}')
[ "$INVALID_GPS" = "400" ] \
  && report "(12a) invalid latitude returns 400" 1 \
  || report "(12a) invalid latitude returns 400" 0 "got $INVALID_GPS"

NOT_FOUND=$(curl -s -o /dev/null -w "%{http_code}" \
  "$GATEWAY_URL/api/locations/99999999" \
  -H "Authorization: Bearer $TOKEN")
[ "$NOT_FOUND" = "404" ] \
  && report "(12b) unknown location id returns 404" 1 \
  || report "(12b) unknown location id returns 404" 0 "got $NOT_FOUND"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "TOTALS: $PASS PASS / $FAIL FAIL"
exit "$FAIL"
