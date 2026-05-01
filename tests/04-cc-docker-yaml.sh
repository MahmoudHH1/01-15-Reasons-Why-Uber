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

# (b) datasource.url must point at postgres:5432 — either directly in the
# service's application.yml, OR via SPRING_DATASOURCE_URL set in
# docker-compose.yaml. Either is spec-compliant; we accept both.
for svc in user-service driver-service ride-service location-service payment-service; do
  yml="$ROOT/$svc/src/main/resources/application.yml"
  [ -f "$yml" ] || continue
  in_yml=0; in_compose=0
  grep -qE 'jdbc:postgresql://postgres:5432' "$yml" && in_yml=1
  awk -v s="$svc:" '
    BEGIN{found=0}
    $0 ~ "^  "s"$" {found=1; next}
    found && /^  [a-z]/ && $0 !~ "^  "s"$" {found=0}
    found && /SPRING_DATASOURCE_URL: jdbc:postgresql:\/\/postgres:5432/ {print "ok"; exit}
  ' "$COMPOSE" | grep -q ok && in_compose=1
  if [ "$in_yml" = "1" ] || [ "$in_compose" = "1" ]; then
    src="yml"; [ "$in_compose" = "1" ] && src="compose-env"
    pass "$svc datasource → postgres:5432 (via $src)"
  else
    fail "$svc datasource → postgres:5432" "neither yml nor compose env points to postgres:5432"
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

# §9.5.f / §9.6.g soft-dep boot test (destructive — runs in a separate
# script `99-manual-soft-dep.sh`, NOT part of run-all.sh because it stops
# Mongo/Redis/ES/Neo4j/Cassandra and would break every other test if
# interleaved). Trigger explicitly:
#   ./tests/99-manual-soft-dep.sh
