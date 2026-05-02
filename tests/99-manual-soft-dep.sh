#!/usr/bin/env bash
# §9.5 step f / §9.6 step g — soft-dependency boot test.
#
# DESTRUCTIVE — stops Mongo, Redis, Elasticsearch, Neo4j, and Cassandra,
# then asserts that every Spring Boot service still answers its health
# endpoint with 200 (per §6.3: only PostgreSQL is a HARD dependency).
#
# This script is intentionally NOT included in tests/run-all.sh because
# stopping the NoSQL stores would break every other assertion if
# interleaved. Run it manually:
#
#   ./tests/99-manual-soft-dep.sh
#
# It restores the stack to a healthy state at the end. If the script is
# killed mid-run, restore manually with:
#
#   docker compose up -d mongo redis elasticsearch neo4j cassandra
#
# Spec citations:
#   §6.3 Dependency Model — "MongoDB, Redis, Elasticsearch, Neo4j,
#        Cassandra: Soft dependencies — if any of these are unavailable,
#        the features that depend on them should degrade gracefully
#        (try-catch), but the service should still start and serve
#        PostgreSQL-based endpoints."
#   §9.5 step f — "Run docker compose up -d → all 5 application services
#        start successfully and connect to their databases."
#   §9.6 step g — "Boot each service in isolation with other services
#        down → must start as long as PostgreSQL is up (hard dep). NoSQL
#        unavailability must not prevent startup."

source "$(dirname "$0")/lib/common.sh"

section "99 §9.5.f / §9.6.g — soft-dep boot (DESTRUCTIVE, manual-only)"

NOSQL_SERVICES="mongo redis elasticsearch neo4j cassandra"
APP_SERVICES="user-service driver-service ride-service location-service payment-service"

cleanup_restore_stack() {
  echo
  echo ">> Restoring NoSQL stack..."
  docker compose up -d $NOSQL_SERVICES >/dev/null 2>&1
  echo ">> Waiting for healthy state..."
  for _ in $(seq 1 60); do
    healthy=$(docker compose ps --format "{{.Service}}\t{{.Health}}" 2>/dev/null \
              | awk '$2=="healthy"' | wc -l)
    [ "$healthy" -ge 6 ] && break
    sleep 2
  done
  echo ">> Restarting application services..."
  docker compose restart $APP_SERVICES >/dev/null 2>&1
  echo ">> Stack restored. Re-run tests/run-all.sh to verify normal flow."
}
trap cleanup_restore_stack EXIT

# 1. Snapshot pre-test health (sanity).
echo ">> Pre-test health check (must be 200 across all 5 services):"
for p in 8081 8082 8083 8084 8085; do
  case $p in
    8081) e=users;;     8082) e=drivers;;  8083) e=rides;;
    8084) e=locations;; 8085) e=payments;;
  esac
  http GET "http://localhost:$p/api/$e/health"
  if [ "$LAST_STATUS" = "200" ]; then
    pass "pre-test $e:$p health → 200"
  else
    fail "pre-test $e:$p health → 200 (got $LAST_STATUS) — abort, stack not in nominal state"
    exit 1
  fi
done

# 2. Stop every NoSQL container.
echo
echo ">> Stopping NoSQL containers: $NOSQL_SERVICES"
docker compose stop $NOSQL_SERVICES >/dev/null 2>&1
sleep 5

# 3. Restart application services so they boot fresh against the missing
#    NoSQL stack (forces them to handle Mongo/Redis/ES/Neo4j/Cassandra
#    being unreachable at startup).
echo ">> Restarting application services with NoSQL stores down..."
docker compose restart $APP_SERVICES >/dev/null 2>&1

# 4. Wait for each service to come back up and answer its health endpoint.
#    Per §6.3 they must start within a reasonable window even with NoSQL
#    down — only PostgreSQL is hard.
echo
echo ">> Polling health endpoints (up to 90s per service)..."
for p in 8081 8082 8083 8084 8085; do
  case $p in
    8081) e=users;;     8082) e=drivers;;  8083) e=rides;;
    8084) e=locations;; 8085) e=payments;;
  esac
  ok=0
  for _ in $(seq 1 30); do
    http GET "http://localhost:$p/api/$e/health"
    if [ "$LAST_STATUS" = "200" ]; then ok=1; break; fi
    sleep 3
  done
  if [ "$ok" = "1" ]; then
    pass "§6.3 + §9.6.g — $e:$p boots with NoSQL down → 200 (only PG is hard dep)"
  else
    fail "§6.3 + §9.6.g — $e:$p boots with NoSQL down → 200" \
         "still answering $LAST_STATUS after 90s — service may have a hard NoSQL dep"
  fi
done
