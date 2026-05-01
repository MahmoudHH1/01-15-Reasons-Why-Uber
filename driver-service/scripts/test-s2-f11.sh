#!/usr/bin/env bash
# End-to-end test for S2-F11 (Index Driver for Search) per Uber_descriptionM2.pdf §10.2.2.
# Exercises the live stack: registers a user via user-service, drives the driver-service
# /api/drivers and /api/drivers/{id}/index endpoints, then asserts cross-DB state in
# Elasticsearch + Mongo + Redis.
#
# Prerequisites:
#   - docker compose up -d postgres mongo redis elasticsearch user-service driver-service
#   - all six containers Healthy (mongo / postgres / redis / elasticsearch healthchecks)
#
# Exit code: number of FAILing assertions. 0 = all green.

set -u

USER_SVC="${USER_SVC:-http://localhost:8081}"
DRIVER_SVC="${DRIVER_SVC:-http://localhost:8082}"
ES="${ES:-http://localhost:9200}"
MONGO_USER="${MONGO_USER:-root}"
MONGO_PASS="${MONGO_PASS:-rootpass}"
MONGO_DB="${MONGO_DB:-ubermongo}"
REDIS_PASS="${REDIS_PASS:-redispass}"
RUN_ID="$(date +%s)$$"
TEST_EMAIL="${TEST_EMAIL:-tester-s2f11-${RUN_ID}@uber.local}"
TEST_PHONE="${TEST_PHONE:-019${RUN_ID: -8}}"
TEST_PWD="${TEST_PWD:-TestPass123}"

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

# ── 0. health roll-up ────────────────────────────────────────────────────────
echo "=== Health checks ==="
USER_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$USER_SVC/api/users/health")
DRIVER_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$DRIVER_SVC/api/drivers/health")
[ "$USER_HEALTH"   = "200" ] && report "(0a) user-service health"   1 || report "(0a) user-service health"   0 "got $USER_HEALTH"
[ "$DRIVER_HEALTH" = "200" ] && report "(0b) driver-service health" 1 || report "(0b) driver-service health" 0 "got $DRIVER_HEALTH"
[ "$FAIL" -gt 0 ] && { echo "stack not healthy — aborting"; exit "$FAIL"; }

# ── 1. obtain JWT via register (unique email per run) ────────────────────────
echo
echo "=== Register $TEST_EMAIL and grab JWT ==="
TOKEN=$(curl -s -X POST "$USER_SVC/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"S2-F11 Tester\",\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PWD\",\"phone\":\"$TEST_PHONE\"}" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))" 2>/dev/null)
if [ -z "$TOKEN" ]; then echo "FAIL  could not obtain JWT — aborting"; exit 1; fi
echo "  token length: ${#TOKEN}"

# ── 2. clean ES + Mongo state ────────────────────────────────────────────────
curl -s -X DELETE "$ES/drivers" -o /dev/null
mongo_eval 'db.driver_events.deleteMany({})' >/dev/null

# ── 3. spec test (a): description='luxury toyota camry' → /index → ES query ──
echo
echo "=== Test (a): description='luxury toyota camry' ==="
RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Toyota Driver\",\"email\":\"a-${RUN_ID}@s2f11.test\",\"phone\":\"021${RUN_ID: -8}\",\"licenseNumber\":\"LIC-A-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SEDAN\",\"description\":\"luxury toyota camry\"}}")
DRIVER_A=$(echo "$RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ "$(echo "$RESP" | tail -1)" = "201" ] && [ -n "$DRIVER_A" ] \
  && report "(a1) create 201 + id"          1 || report "(a1) create 201 + id"          0 "got $(echo "$RESP" | tail -1) / id=$DRIVER_A"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "200" ] && report "(a2) /index 200 (explicit)"      1 || report "(a2) /index 200 (explicit)"      0 "got $CODE"
sleep 1
HITS=$(curl -s "$ES/drivers/_search?q=toyota" | python3 -c "import sys,json;print(json.load(sys.stdin)['hits']['total']['value'])")
[ "$HITS" -ge 1 ] && report "(a3) ES q=toyota finds driver"     1 || report "(a3) ES q=toyota finds driver"     0 "hits=$HITS"

# ── 4. spec test (b): missing description key → empty string in ES ───────────
echo
echo "=== Test (b): no description key ==="
RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"NoDesc\",\"email\":\"b-${RUN_ID}@s2f11.test\",\"phone\":\"022${RUN_ID: -8}\",\"licenseNumber\":\"LIC-B-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SUV\"}}")
DRIVER_B=$(echo "$RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ "$(echo "$RESP" | tail -1)" = "201" ] && report "(b1) create 201"               1 || report "(b1) create 201"               0 "got $(echo "$RESP" | tail -1)"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_B/index" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "200" ] && report "(b2) /index 200 empty desc"      1 || report "(b2) /index 200 empty desc"      0 "got $CODE"
sleep 1
DESC=$(curl -s "$ES/drivers/_doc/$DRIVER_B" | python3 -c "import sys,json;print(json.load(sys.stdin).get('_source',{}).get('description','MISSING'))")
[ "$DESC" = "" ] && report "(b3) ES description==''"            1 || report "(b3) ES description==''"            0 "got '$DESC'"

# ── 5. spec test (c): unknown id → 404 ───────────────────────────────────────
echo
echo "=== Test (c): unknown id 404 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/999999/index" -H "Authorization: Bearer $TOKEN")
[ "$CODE" = "404" ] && report "(c) /index unknown id 404"        1 || report "(c) /index unknown id 404"        0 "got $CODE"

# ── 6. spec test (d): auto-index on PUT update ───────────────────────────────
echo
echo "=== Test (d): auto-index on PUT update ==="
NEW_NAME="UpdatedToyota$$"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_A" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"$NEW_NAME\",\"email\":\"a-${RUN_ID}@s2f11.test\",\"phone\":\"021${RUN_ID: -8}\",\"licenseNumber\":\"LIC-A-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SEDAN\",\"description\":\"luxury toyota camry\"}}")
[ "$CODE" = "200" ] && report "(d1) PUT 200"                     1 || report "(d1) PUT 200"                     0 "got $CODE"
sleep 1
HITS=$(curl -s "$ES/drivers/_search?q=name:$NEW_NAME" | python3 -c "import sys,json;print(json.load(sys.stdin)['hits']['total']['value'])")
[ "$HITS" -ge 1 ] && report "(d2) auto-index reflects new name"  1 || report "(d2) auto-index reflects new name"  0 "hits=$HITS"

# ── 7. spec test (e): no token → 401 ─────────────────────────────────────────
echo
echo "=== Test (e): no token 401 ==="
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index")
[ "$CODE" = "401" ] && report "(e) no-token 401"                 1 || report "(e) no-token 401"                 0 "got $CODE"

# ── 8. cross-DB: Mongo INDEXED events with all three sources ─────────────────
echo
echo "=== Cross-DB: Mongo events ==="
EXP=$(mongo_eval 'db.driver_events.countDocuments({"details.source":"explicit"})'         | tr -d '[:space:]')
AC=$( mongo_eval 'db.driver_events.countDocuments({"details.source":"auto_crud_create"})' | tr -d '[:space:]')
AU=$( mongo_eval 'db.driver_events.countDocuments({"details.source":"auto_crud_update"})' | tr -d '[:space:]')
[ "$EXP" -ge 1 ] && report "(m1) INDEXED source=explicit ($EXP)"          1 || report "(m1) INDEXED source=explicit"          0 "$EXP"
[ "$AC"  -ge 1 ] && report "(m2) INDEXED source=auto_crud_create ($AC)"   1 || report "(m2) INDEXED source=auto_crud_create"   0 "$AC"
[ "$AU"  -ge 1 ] && report "(m3) INDEXED source=auto_crud_update ($AU)"   1 || report "(m3) INDEXED source=auto_crud_update"   0 "$AU"

# ── 9. DRIVER_DELETED on CRUD delete ─────────────────────────────────────────
echo
echo "=== DRIVER_DELETED on CRUD delete ==="
curl -s -X DELETE "$DRIVER_SVC/api/drivers/$DRIVER_B" -H "Authorization: Bearer $TOKEN" -o /dev/null
sleep 1
DD=$(mongo_eval 'db.driver_events.countDocuments({action:"DRIVER_DELETED"})' | tr -d '[:space:]')
[ "$DD" -ge 1 ] && report "(m4) DRIVER_DELETED ($DD)"            1 || report "(m4) DRIVER_DELETED"            0 "$DD"
ED=$(curl -s -o /dev/null -w "%{http_code}" "$ES/drivers/_doc/$DRIVER_B")
[ "$ED" = "404" ] && report "(m5) ES doc removed on delete"      1 || report "(m5) ES doc removed on delete"      0 "got $ED"

# ── 10. cache invalidation (driver-service::S2-F10::*) ───────────────────────
echo
echo "=== Cache invalidation on /index, PUT, DELETE ==="
redis_cmd SET "driver-service::S2-F10::probe" "x" >/dev/null
BEFORE=$(redis_cmd GET "driver-service::S2-F10::probe" | tail -1)
curl -s -o /dev/null -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index" -H "Authorization: Bearer $TOKEN"
sleep 1
AFTER=$(redis_cmd GET "driver-service::S2-F10::probe" | tail -1)
[ "$BEFORE" = "x" ] && [ -z "$AFTER" ] \
  && report "(c1) /index invalidates S2-F10::*"           1 || report "(c1) /index invalidates S2-F10::*"           0 "before='$BEFORE' after='$AFTER'"

# CRUD PUT path also invalidates per cache-matrix.md
redis_cmd SET "driver-service::S2-F10::probe2" "y" >/dev/null
curl -s -o /dev/null -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_A" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"PutCacheCheck\",\"email\":\"a-${RUN_ID}@s2f11.test\",\"phone\":\"021${RUN_ID: -8}\",\"licenseNumber\":\"LIC-A-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"SEDAN\"}}"
sleep 1
AFTER=$(redis_cmd GET "driver-service::S2-F10::probe2" | tail -1)
[ -z "$AFTER" ] && report "(c2) CRUD PUT invalidates S2-F10::*"           1 || report "(c2) CRUD PUT invalidates S2-F10::*"           0 "after='$AFTER'"

# NOTE: cache-matrix.md does NOT list driver::{id} for the /index path (only for
# CRUD writes that mutate PG). We deliberately do not assert it here — testing
# implementation defaults beyond spec would over-constrain S2-F10/S2-F12 work.

# ── 11. Boundary: long description, special chars, status/rating reflected ───
echo
echo "=== Boundary cases ==="

# (b4) very long description (≥1KB)
LONG=$(printf 'x%.0s' {1..1500})
RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"LongDesc\",\"email\":\"long-${RUN_ID}@s2f11.test\",\"phone\":\"023${RUN_ID: -8}\",\"licenseNumber\":\"LIC-LONG-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"VAN\",\"description\":\"$LONG\"}}")
DRIVER_LONG=$(echo "$RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ "$(echo "$RESP" | tail -1)" = "201" ] && report "(b4) create with 1500-char description 201"  1 || report "(b4) create with 1500-char description 201"  0 "got $(echo "$RESP" | tail -1)"
sleep 1
DESC_LEN=$(curl -s "$ES/drivers/_doc/$DRIVER_LONG" | python3 -c "import sys,json;print(len(json.load(sys.stdin).get('_source',{}).get('description','')))")
[ "$DESC_LEN" = "1500" ] && report "(b5) ES preserves full long description"           1 || report "(b5) ES preserves full long description"           0 "got len=$DESC_LEN"

# (b6) special characters in description
RESP=$(curl -s -w '\n%{http_code}' -X POST "$DRIVER_SVC/api/drivers" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"Special\",\"email\":\"sp-${RUN_ID}@s2f11.test\",\"phone\":\"024${RUN_ID: -8}\",\"licenseNumber\":\"LIC-SP-${RUN_ID}\",\"status\":\"AVAILABLE\",\"vehicleDetails\":{\"vehicleType\":\"LUXURY\",\"description\":\"luxury & comfort 2026 (top-tier!)\"}}")
DRIVER_SP=$(echo "$RESP" | head -n -1 | python3 -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
[ "$(echo "$RESP" | tail -1)" = "201" ] && report "(b6) create with special chars 201"     1 || report "(b6) create with special chars 201"     0 "got $(echo "$RESP" | tail -1)"
sleep 1
DESC=$(curl -s "$ES/drivers/_doc/$DRIVER_SP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('_source',{}).get('description',''))")
[ "$DESC" = "luxury & comfort 2026 (top-tier!)" ] && report "(b7) ES preserves special chars exactly" 1 || report "(b7) ES preserves special chars exactly" 0 "got '$DESC'"

# (b8) status update via PUT /availability reflected in ES
curl -s -o /dev/null -X PUT "$DRIVER_SVC/api/drivers/$DRIVER_SP/availability" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"status":"BUSY"}'
sleep 1
ES_STATUS=$(curl -s "$ES/drivers/_doc/$DRIVER_SP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('_source',{}).get('status',''))")
[ "$ES_STATUS" = "BUSY" ] && report "(b8) availability change reflected in ES"          1 || report "(b8) availability change reflected in ES"          0 "got '$ES_STATUS'"

# (b9) all 6 indexed fields present in ES doc
ES_FIELDS=$(curl -s "$ES/drivers/_doc/$DRIVER_SP" | python3 -c "
import sys,json
src=json.load(sys.stdin).get('_source',{})
need=['id','name','vehicleType','description','rating','status']
print(','.join(f for f in need if f in src))")
[ "$ES_FIELDS" = "id,name,vehicleType,description,rating,status" ] && report "(b9) ES doc has all 6 indexed fields" 1 || report "(b9) ES doc has all 6 indexed fields" 0 "got '$ES_FIELDS'"

# ── 12. Auth edge cases ──────────────────────────────────────────────────────
echo
echo "=== Auth edge cases ==="

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index" -H "Authorization: NotBearer xxx")
[ "$CODE" = "401" ] && report "(au1) wrong scheme (not Bearer) 401"      1 || report "(au1) wrong scheme (not Bearer) 401"      0 "got $CODE"

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index" -H "Authorization: Bearer ")
[ "$CODE" = "401" ] && report "(au2) empty bearer 401"                   1 || report "(au2) empty bearer 401"                   0 "got $CODE"

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index" -H "Authorization: Bearer junk.junk.junk")
[ "$CODE" = "401" ] && report "(au3) garbage JWT 401"                    1 || report "(au3) garbage JWT 401"                    0 "got $CODE"

# Tampered signature: take real token, flip last char
TAMPERED="${TOKEN%?}A"
[ "$TAMPERED" = "$TOKEN" ] && TAMPERED="${TOKEN%?}B"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$DRIVER_SVC/api/drivers/$DRIVER_A/index" -H "Authorization: Bearer $TAMPERED")
[ "$CODE" = "401" ] && report "(au4) tampered signature 401"             1 || report "(au4) tampered signature 401"             0 "got $CODE"

# Health endpoint must remain public (no token, returns 200) — soft-dep on JWT
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$DRIVER_SVC/api/drivers/health")
[ "$CODE" = "200" ] && report "(au5) /health stays public (no token)"    1 || report "(au5) /health stays public (no token)"    0 "got $CODE"

# ── 13. Idempotency: /index x N produces 1 ES doc, fresh INDEXED events ─────
echo
echo "=== Idempotency ==="
mongo_eval "db.driver_events.deleteMany({'details.driverId': NumberLong('$DRIVER_SP')})" >/dev/null
for _ in 1 2 3 4 5; do
  curl -s -o /dev/null -X POST "$DRIVER_SVC/api/drivers/$DRIVER_SP/index" -H "Authorization: Bearer $TOKEN"
done
sleep 1
ES_COUNT=$(curl -s "$ES/drivers/_search?q=_id:$DRIVER_SP" | python3 -c "import sys,json;print(json.load(sys.stdin)['hits']['total']['value'])")
[ "$ES_COUNT" = "1" ] && report "(id1) 5x /index → ES has exactly 1 doc"  1 || report "(id1) 5x /index → ES has exactly 1 doc"  0 "got $ES_COUNT"

EVT_COUNT=$(mongo_eval "db.driver_events.countDocuments({action:'INDEXED','details.driverId': NumberLong('$DRIVER_SP'), 'details.source':'explicit'})" | grep -E '^[0-9]+$' | tail -1)
[ "$EVT_COUNT" = "5" ] && report "(id2) 5x /index → 5 INDEXED Mongo events" 1 || report "(id2) 5x /index → 5 INDEXED Mongo events" 0 "got $EVT_COUNT"

# ── 14. Action vocabulary + indexedFields shape ──────────────────────────────
echo
echo "=== Action vocabulary + event shape ==="
ACTIONS=$(mongo_eval 'JSON.stringify(db.driver_events.distinct("action"))' | tr -d '[:space:]')
case "$ACTIONS" in
  *INDEXED*DRIVER_DELETED*|*DRIVER_DELETED*INDEXED*) report "(v1) Mongo actions ⊆ {INDEXED, DRIVER_DELETED}" 1;;
  *INDEXED*) [ "$(echo "$ACTIONS" | grep -oE '[A-Z_]+' | wc -l)" -le 2 ] && report "(v1) Mongo actions ⊆ {INDEXED, DRIVER_DELETED}" 1 || report "(v1) Mongo actions ⊆ {INDEXED, DRIVER_DELETED}" 0 "got $ACTIONS";;
  *) report "(v1) Mongo actions ⊆ {INDEXED, DRIVER_DELETED}" 0 "got $ACTIONS";;
esac

INDEXED_FIELDS_OK=$(mongo_eval '
var doc = db.driver_events.findOne({action:"INDEXED"});
if (!doc) print("NO_DOC");
else if (JSON.stringify(doc.details.indexedFields) === JSON.stringify(["id","name","vehicleType","description","rating","status"])) print("OK");
else print("MISMATCH:" + JSON.stringify(doc.details.indexedFields));
' | tr -d '[:space:]')
[ "$INDEXED_FIELDS_OK" = "OK" ] && report "(v2) details.indexedFields = [id,name,vehicleType,description,rating,status]" 1 || report "(v2) details.indexedFields = expected list" 0 "got $INDEXED_FIELDS_OK"

# ── 15. List endpoint NOT cached ─────────────────────────────────────────────
echo
echo "=== GET /api/drivers must NOT cache (cache-matrix excludes lists) ==="
redis_cmd --scan --pattern 'driver-service::driver::list*' | xargs -r -I{} redis_cmd DEL '{}' >/dev/null
curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" "$DRIVER_SVC/api/drivers"
sleep 1
LIST_KEYS=$(redis_cmd --scan --pattern 'driver-service::*list*' | wc -l)
[ "$LIST_KEYS" = "0" ] && report "(L1) GET /api/drivers does not create list cache key" 1 || report "(L1) GET /api/drivers does not create list cache key" 0 "found $LIST_KEYS keys"

echo
echo "============================================================"
echo "TOTALS: $PASS PASS / $FAIL FAIL"
echo "============================================================"
exit "$FAIL"
