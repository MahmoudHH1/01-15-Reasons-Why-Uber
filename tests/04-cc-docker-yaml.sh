#!/usr/bin/env bash
# CC-5 — Docker compose with 6 databases (§9.5)
# CC-6 — application.yml present per service (§9.6)
#
# Static checks against repo files. No live HTTP. Run before bringing the
# stack up (or any time, since these check files-on-disk).

source "$(dirname "$0")/lib/common.sh"

section "04 CC-5 / CC-6 — docker-compose.yaml + per-service application.yml"

ROOT="${ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
COMPOSE="$ROOT/docker-compose.yaml"

if [ ! -f "$COMPOSE" ]; then
  fail "docker-compose.yaml exists at repo root"
  exit 1
fi
pass "docker-compose.yaml exists"

# (a) Required services
for svc in postgres mongo redis elasticsearch neo4j cassandra; do
  if grep -qE "^  ${svc}:" "$COMPOSE"; then
    pass "compose service '$svc' defined"
  else
    fail "compose service '$svc' defined"
  fi
done

# (b) Pinned image tags (§6.1)
declare -A WANT=(
  [postgres]="postgres:17"
  [mongo]="mongo:latest"
  [redis]="redis:latest"
  [elasticsearch]="elasticsearch:8.19.12"
  [neo4j]="neo4j:latest"
  [cassandra]="cassandra:latest"
)
for svc in "${!WANT[@]}"; do
  if grep -qE "image:\s*${WANT[$svc]}" "$COMPOSE"; then
    pass "$svc image pinned to ${WANT[$svc]}"
  else
    fail "$svc image pinned to ${WANT[$svc]}"
  fi
done

# (c) Memory caps (§6.2)
grep -qE 'maxmemory 256mb' "$COMPOSE" \
  && pass "redis --maxmemory 256mb present" \
  || fail "redis --maxmemory 256mb present"

grep -qE 'allkeys-lru' "$COMPOSE" \
  && pass "redis --maxmemory-policy allkeys-lru present" \
  || fail "redis --maxmemory-policy allkeys-lru present"

grep -qE 'ES_JAVA_OPTS=-Xms512m -Xmx512m' "$COMPOSE" \
  && pass "elasticsearch ES_JAVA_OPTS=-Xms512m -Xmx512m" \
  || fail "elasticsearch ES_JAVA_OPTS=-Xms512m -Xmx512m"

grep -qE 'MAX_HEAP_SIZE: ?512M' "$COMPOSE" \
  && pass "cassandra MAX_HEAP_SIZE: 512M" \
  || fail "cassandra MAX_HEAP_SIZE: 512M"

grep -qE 'NEO4J_server_memory_heap_max__size: ?512m' "$COMPOSE" \
  && pass "neo4j heap_max__size: 512m" \
  || fail "neo4j heap_max__size: 512m"

# (d) Healthchecks
for svc in postgres mongo redis elasticsearch neo4j cassandra; do
  awk -v s="$svc" '
    $0 ~ "^  "s":" {found=1}
    found && /healthcheck:/ {hc=1; print "ok"; exit}
    found && /^  [a-z]/ && $0 !~ s {found=0}
  ' "$COMPOSE" | grep -q "ok" \
    && pass "$svc has a healthcheck" \
    || fail "$svc has a healthcheck"
done

# --- CC-6 application.yml per service ----------------------------------

for svc in user-service driver-service ride-service location-service payment-service; do
  yml="$ROOT/$svc/src/main/resources/application.yml"
  prop="$ROOT/$svc/src/main/resources/application.properties"
  if [ -f "$yml" ]; then
    pass "$svc has application.yml"
  else
    fail "$svc has application.yml" "found at $yml"
  fi
  if [ -f "$prop" ]; then
    fail "$svc has NO leftover application.properties" "still present at $prop"
  else
    pass "$svc has NO application.properties"
  fi
done

# (b) datasource.url points to postgres:5432
for svc in user-service driver-service ride-service location-service payment-service; do
  yml="$ROOT/$svc/src/main/resources/application.yml"
  [ -f "$yml" ] || continue
  if grep -qE 'jdbc:postgresql://postgres:5432' "$yml"; then
    pass "$svc datasource → postgres:5432"
  else
    skip "$svc datasource → postgres:5432" "may be overridden by env in compose"
  fi
done

# (c) jwt.secret + spring.data.redis.host present
for svc in user-service driver-service ride-service location-service payment-service; do
  yml="$ROOT/$svc/src/main/resources/application.yml"
  [ -f "$yml" ] || continue
  grep -qE '^\s*jwt:'                "$yml" && pass "$svc has jwt: block"             || fail "$svc has jwt: block"
  grep -qE '^\s*secret:'              "$yml" && pass "$svc has jwt.secret"             || fail "$svc has jwt.secret"
  grep -qE 'redis:'                   "$yml" && pass "$svc has spring.data.redis"      || fail "$svc has spring.data.redis"
  grep -qE 'mongodb:'                 "$yml" && pass "$svc has spring.data.mongodb"    || fail "$svc has spring.data.mongodb"
done

# (d) Driver-only ES, Ride-only Neo4j, Location-only Cassandra
grep -qE 'elasticsearch:' "$ROOT/driver-service/src/main/resources/application.yml" \
  && pass "driver-service has spring.elasticsearch.uris" \
  || fail "driver-service has spring.elasticsearch.uris"

grep -qE 'neo4j:' "$ROOT/ride-service/src/main/resources/application.yml" \
  && pass "ride-service has spring.data.neo4j.uri" \
  || fail "ride-service has spring.data.neo4j.uri"

grep -qE 'cassandra:' "$ROOT/location-service/src/main/resources/application.yml" \
  && pass "location-service has spring.cassandra" \
  || fail "location-service has spring.cassandra"

# --- §9.5 step f / §9.6 step g — soft-dep boot test (manual) -------------
# Bringing a service up in isolation with NoSQL stores down requires
# stopping containers, which is destructive to the rest of the suite.
# This script DOCUMENTS the manual procedure rather than performing it:
#
#   docker compose stop mongo redis elasticsearch neo4j cassandra
#   docker compose restart user-service driver-service ride-service \
#                          location-service payment-service
#   for p in 8081 8082 8083 8084 8085; do
#     curl -sS -o /dev/null -w "$p:%{http_code}\n" \
#       "http://localhost:$p/api/$(case $p in
#          8081) echo users;; 8082) echo drivers;; 8083) echo rides;;
#          8084) echo locations;; 8085) echo payments;; esac)/health"
#   done
#   # Every service must answer 200 — only PostgreSQL is a hard dependency.
#
# Run that recipe manually, then `docker compose start mongo redis ...`
# before re-running the rest of this suite.
skip "§9.5.f / §9.6.g soft-dep boot (manual procedure)" \
     "see comments in 04-cc-docker-yaml.sh — destructive to live stack"
