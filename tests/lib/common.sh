#!/usr/bin/env bash
# Shared helpers for the M2 test suite.
# Sourced by every tests/*.sh script. Provides:
#   - HTTP/curl wrappers with auto status-code assertion
#   - JWT registration/login helpers (uses the live user-service)
#   - Redis / Mongo / Cassandra / Neo4j / Elasticsearch helpers
#   - PASS/FAIL counters and a TOTALS line on exit
#
# Configurable via environment (defaults match docker-compose.yaml):
#   USER_URL, DRIVER_URL, RIDE_URL, LOCATION_URL, PAYMENT_URL
#   REDIS_HOST, REDIS_PORT, REDIS_PASSWORD
#   MONGO_HOST, MONGO_PORT, MONGO_USER, MONGO_PASSWORD, MONGO_DB
#   ES_URL, NEO4J_URL, NEO4J_USER, NEO4J_PASSWORD
#   CASSANDRA_HOST, CASSANDRA_PORT, CASSANDRA_KEYSPACE

set -o pipefail
# Note: deliberately NOT using `set -u` — many helpers reference optional
# vars (PAY_ID, FAIL_PID, ...) that are populated only if a prior call
# succeeded. With `-u` the whole script aborts on the first unset var,
# masking everything below.

USER_URL="${USER_URL:-http://localhost:8081}"
DRIVER_URL="${DRIVER_URL:-http://localhost:8082}"
RIDE_URL="${RIDE_URL:-http://localhost:8083}"
LOCATION_URL="${LOCATION_URL:-http://localhost:8084}"
PAYMENT_URL="${PAYMENT_URL:-http://localhost:8085}"

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-redispass}"

MONGO_HOST="${MONGO_HOST:-localhost}"
MONGO_PORT="${MONGO_PORT:-27017}"
MONGO_USER="${MONGO_USER:-root}"
MONGO_PASSWORD="${MONGO_PASSWORD:-rootpass}"
MONGO_DB="${MONGO_DB:-ubermongo}"

ES_URL="${ES_URL:-http://localhost:9200}"
NEO4J_URL="${NEO4J_URL:-bolt://localhost:7687}"
NEO4J_USER="${NEO4J_USER:-neo4j}"
NEO4J_PASSWORD="${NEO4J_PASSWORD:-neo4jpass}"

CASSANDRA_HOST="${CASSANDRA_HOST:-localhost}"
CASSANDRA_PORT="${CASSANDRA_PORT:-9042}"
CASSANDRA_KEYSPACE="${CASSANDRA_KEYSPACE:-uberks}"

RUN_ID="${RUN_ID:-$(date +%s)$$}"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
CURRENT_SECTION=""

LAST_BODY=""
LAST_STATUS=""

c_red()    { printf '\033[31m%s\033[0m' "$1"; }
c_green()  { printf '\033[32m%s\033[0m' "$1"; }
c_yellow() { printf '\033[33m%s\033[0m' "$1"; }
c_cyan()   { printf '\033[36m%s\033[0m' "$1"; }

section() {
  CURRENT_SECTION="$1"
  echo
  echo "=================================================================="
  c_cyan "# $CURRENT_SECTION"
  echo
  echo "=================================================================="
}

pass() {
  PASS_COUNT=$((PASS_COUNT+1))
  c_green "  PASS"; echo "  $1"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT+1))
  c_red   "  FAIL"; echo "  $1"
  if [ -n "${2:-}" ]; then echo "        $2"; fi
  if [ -n "$LAST_STATUS" ] || [ -n "$LAST_BODY" ]; then
    echo "        last status=$LAST_STATUS"
    echo "        last body=$(echo "$LAST_BODY" | head -c 400)"
  fi
}

skip() {
  SKIP_COUNT=$((SKIP_COUNT+1))
  c_yellow "  SKIP"; echo "  $1${2:+ ($2)}"
}

# Issue an HTTP request. Captures body in LAST_BODY and status in LAST_STATUS.
# Usage: http METHOD URL [-H "header:val"]... [-d 'json']
#
# Does NOT echo the body to stdout (that pollutes `$(http ...)` captures and
# was a bug). Use `$LAST_BODY` to read it explicitly.
http() {
  local method="$1"; shift
  local url="$1";    shift
  local tmp; tmp="$(mktemp)"
  LAST_STATUS=$(curl -sS -o "$tmp" -w "%{http_code}" -X "$method" "$url" "$@" 2>/dev/null || echo "000")
  LAST_BODY="$(cat "$tmp")"
  rm -f "$tmp"
}

http_auth() {
  local method="$1"; shift
  local url="$1";    shift
  local token="$1";  shift
  http "$method" "$url" -H "Authorization: Bearer $token" "$@"
}

assert_status() {
  local expected="$1"
  local label="$2"
  if [ "$LAST_STATUS" = "$expected" ]; then
    pass "$label  [$expected]"
  else
    fail "$label" "expected status $expected, got $LAST_STATUS"
  fi
}

assert_status_in() {
  local label="$1"; shift
  local got="$LAST_STATUS"
  for s in "$@"; do
    if [ "$got" = "$s" ]; then pass "$label  [$got in {$*}]"; return 0; fi
  done
  fail "$label" "expected one of {$*}, got $got"
}

assert_json_field() {
  local field="$1"
  local expected="$2"
  local label="$3"
  local got
  got="$(echo "$LAST_BODY" | jq -r "$field" 2>/dev/null)"
  if [ "$got" = "$expected" ]; then
    pass "$label  ($field=$expected)"
  else
    fail "$label" "expected $field=$expected, got $got"
  fi
}

assert_body_contains() {
  local needle="$1"
  local label="$2"
  if echo "$LAST_BODY" | grep -q -- "$needle"; then
    pass "$label  (contains \"$needle\")"
  else
    fail "$label" "body did not contain \"$needle\""
  fi
}

# Register a user. Echoes the JWT token on stdout (or empty string on
# failure — never crashes the caller). Each call uses a different prefix
# digit derived from $RANDOM so concurrent registrations don't collide.
register_user() {
  local prefix="${1:-u}"
  local salt="${RANDOM}${RANDOM}"
  local email="${prefix}-${salt}-${RUN_ID}@example.com"
  local phone="+2${salt:0:11}"
  local body
  body=$(curl -sS -X POST "$USER_URL/api/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Test ${prefix}\",\"email\":\"${email}\",\"password\":\"securePassword123\",\"phone\":\"${phone}\"}" 2>/dev/null)
  if echo "$body" | jq -e '.token' >/dev/null 2>&1; then
    echo "$body" | jq -r '.token'
  else
    echo ""
  fi
}

login_user() {
  local email="$1"
  local password="${2:-securePassword123}"
  local body
  body=$(curl -sS -X POST "$USER_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"${email}\",\"password\":\"${password}\"}" 2>/dev/null)
  if echo "$body" | jq -e '.token' >/dev/null 2>&1; then
    echo "$body" | jq -r '.token'
  else
    echo ""
  fi
}

# Decode the userId (uid claim) from a JWT. No signature verification — we
# only need the value to send back to the same service.
jwt_uid() {
  local token="$1"
  local payload
  payload="$(echo "$token" | cut -d. -f2)"
  # base64url -> base64
  payload="$(echo "$payload" | tr '_-' '/+')"
  case $(( ${#payload} % 4 )) in 2) payload="${payload}==";; 3) payload="${payload}=";; esac
  echo "$payload" | base64 -d 2>/dev/null | jq -r '.uid // empty'
}

jwt_role() {
  local token="$1"
  local payload="$(echo "$token" | cut -d. -f2 | tr '_-' '/+')"
  case $(( ${#payload} % 4 )) in 2) payload="${payload}==";; 3) payload="${payload}=";; esac
  echo "$payload" | base64 -d 2>/dev/null | jq -r '.role // empty'
}

# ----- Redis / Mongo helpers --------------------------------------------
#
# We prefer host tooling (redis-cli, mongosh) when available. Otherwise we
# transparently fall back to `docker compose exec -T <container> ...` so the
# suite works on machines that have only Docker installed. Override with
# REDIS_VIA / MONGO_VIA env vars if you need to force one mode.

REDIS_CONTAINER="${REDIS_CONTAINER:-uber-redis}"
MONGO_CONTAINER="${MONGO_CONTAINER:-uber-mongo}"

if command -v redis-cli >/dev/null 2>&1; then
  REDIS_VIA="${REDIS_VIA:-host}"
else
  REDIS_VIA="${REDIS_VIA:-docker}"
fi
if command -v mongosh >/dev/null 2>&1; then
  MONGO_VIA="${MONGO_VIA:-host}"
else
  MONGO_VIA="${MONGO_VIA:-docker}"
fi

redis_run() {
  case "$REDIS_VIA" in
    host)
      redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -a "$REDIS_PASSWORD" --no-auth-warning "$@"
      ;;
    *)
      docker exec -i "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@"
      ;;
  esac
}

redis_keys() {
  redis_run KEYS "$1" 2>/dev/null | tr -d '\r'
}

redis_count_keys() {
  local out
  out="$(redis_keys "$1")"
  [ -z "$out" ] && { echo 0; return; }
  echo "$out" | grep -c -v '^$' || echo 0
}

redis_flush_pattern() {
  local keys
  keys="$(redis_keys "$1")"
  if [ -n "$keys" ]; then
    echo "$keys" | xargs -r -I{} redis_run DEL {} >/dev/null 2>&1
  fi
}

mongo_eval() {
  local js="$1"
  case "$MONGO_VIA" in
    host)
      mongosh "mongodb://$MONGO_USER:$MONGO_PASSWORD@$MONGO_HOST:$MONGO_PORT/$MONGO_DB?authSource=admin" \
        --quiet --eval "$js" 2>/dev/null
      ;;
    *)
      docker exec -i "$MONGO_CONTAINER" mongosh \
        "mongodb://$MONGO_USER:$MONGO_PASSWORD@127.0.0.1:27017/$MONGO_DB?authSource=admin" \
        --quiet --eval "$js" 2>/dev/null
      ;;
  esac
}

mongo_count() {
  local collection="$1"
  local filter="${2:-{}}"
  local out
  out="$(mongo_eval "db.${collection}.countDocuments(${filter})" | tr -d '\r' | tail -1)"
  case "$out" in ''|*[!0-9]*) echo 0 ;; *) echo "$out" ;; esac
}

mongo_find_recent() {
  local collection="$1"
  local filter="${2:-{}}"
  mongo_eval "db.${collection}.find(${filter}).sort({timestamp:-1}).limit(5).toArray()"
}

mongo_clear() {
  mongo_eval "db.${1}.deleteMany({})" >/dev/null
}

# ----- Final summary ------------------------------------------------------

summary() {
  echo
  echo "------------------------------------------------------------------"
  echo "TOTALS: $PASS_COUNT PASS / $FAIL_COUNT FAIL / $SKIP_COUNT SKIP"
  echo "------------------------------------------------------------------"
  exit $FAIL_COUNT
}

trap summary EXIT
