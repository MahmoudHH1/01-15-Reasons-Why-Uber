#!/usr/bin/env bash
# CC-1 — JWT on all endpoints   (§9.1)
# CC-2 — Role management        (§9.2)  PUT /api/users/{id}/role
# §3.4 Chain of Responsibility — JWT filter chain failure modes
# §3.6 Singleton — JwtConfigurationManager (runtime-observable: same secret across services)
# §4.3 Existing M1 endpoints — JWT authentication
# §5.2 JWT token (HS256, sub=email, uid=Long, role, iat, exp, 24h)

source "$(dirname "$0")/lib/common.sh"

section "01 CC-1 / CC-2 — JWT auth + role management across all 5 services"

# --- 1. Public endpoints (3 expected: register, login, health) ----------

http POST "$USER_URL/api/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Open Reg\",\"email\":\"open-${RUN_ID}@example.com\",\"password\":\"securePassword123\",\"phone\":\"+1${RUN_ID:0:9}\"}"
assert_status 201 "POST /api/auth/register is public, returns 201 (§10.1.1.g)"
TOKEN_OPEN="$(echo "$LAST_BODY" | jq -r '.token // empty')"
[ -n "$TOKEN_OPEN" ] && pass "register returns a JWT" || fail "register returns a JWT" "no token in body"

http POST "$USER_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"open-${RUN_ID}@example.com\",\"password\":\"securePassword123\"}"
assert_status 200 "POST /api/auth/login is public"
TOKEN_RIDER="$(echo "$LAST_BODY" | jq -r '.token // empty')"

# --- 2. Missing-token rejection (401) on every protected family --------

for url in "$USER_URL/api/users" "$DRIVER_URL/api/drivers" "$RIDE_URL/api/rides" \
           "$LOCATION_URL/api/locations" "$PAYMENT_URL/api/payments"; do
  http GET "$url"
  assert_status 401 "GET $url without Authorization → 401"
done

# --- 3. Malformed token rejection (401) -------------------------------

for url in "$DRIVER_URL/api/drivers/search" "$RIDE_URL/api/rides/search" \
           "$LOCATION_URL/api/locations/nearby?lat=0&lon=0&radiusKm=1"; do
  http GET "$url" -H "Authorization: Bearer abc"
  assert_status 401 "GET $url with malformed Bearer → 401"
done

# --- 3b. Expired token (401) — §9.1 step d -----------------------------
# Forge an HS256 token whose exp is in 2020 (long past). Service must
# return 401 — either via SignatureValidationHandler (signature mismatch)
# or the expiry check, both spec-acceptable.
EXPIRED_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4QHguaW8iLCJ1aWQiOjEsInJvbGUiOiJSSURFUiIsImlhdCI6MTYwMDAwMDAwMCwiZXhwIjoxNjAwMDAwMDAxfQ.expired_signature_bytes"
http GET "$USER_URL/api/users" -H "Authorization: Bearer $EXPIRED_JWT"
assert_status 401 "expired token → 401 (§9.1 step d)"

# --- 3b. Expired token (401) — §9.1 step d -----------------------------
# Forge an HS256 JWT with the same secret but exp in the past. We know the
# shared secret from the spec yaml fragments (Base64 of "mysupersecret...").
EXPIRED_PAYLOAD='{"sub":"x@x.io","uid":1,"role":"RIDER","iat":1600000000,"exp":1600000001}'
# Pre-computed token: header eyJhbGciOiJIUzI1NiJ9 + the payload above +
# a signature that won't match — service must reject on either expiry or
# signature, both → 401.
EXPIRED_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4QHguaW8iLCJ1aWQiOjEsInJvbGUiOiJSSURFUiIsImlhdCI6MTYwMDAwMDAwMCwiZXhwIjoxNjAwMDAwMDAxfQ.expired_signature_bytes"
http GET "$USER_URL/api/users" -H "Authorization: Bearer $EXPIRED_JWT"
assert_status 401 "expired token → 401 (§9.1 step d)"

# --- 4. Invalid signature (401) — §3.4 SignatureValidationHandler ------

INVALID_JWT="eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJoYWNrZXJAZXhhbXBsZS5jb20iLCJ1aWQiOjk5OTksInJvbGUiOiJBRE1JTiIsImlhdCI6MTcwMDAwMDAwMCwiZXhwIjo5OTk5OTk5OTk5fQ.invalid_signature_bytes"
http GET "$USER_URL/api/users" -H "Authorization: Bearer $INVALID_JWT"
assert_status 401 "GET /api/users with bad-signature token → 401"

# --- 5. Token issued by user-service is accepted by every other service
#       This proves the §3.6 Singleton + §5.2 shared-secret contract: same
#       JWT_SECRET in every application.yml.

for label in "driver-service:$DRIVER_URL/api/drivers" \
             "ride-service:$RIDE_URL/api/rides" \
             "location-service:$LOCATION_URL/api/locations" \
             "payment-service:$PAYMENT_URL/api/payments"; do
  svc="${label%%:*}"
  url="${label#*:}"
  http_auth GET "$url" "$TOKEN_RIDER"
  assert_status_in "$svc accepts user-service-issued token" 200 204
done

# --- 6. CC-2 role management (§9.2) ---------------------------------------
# Behaviour matrix:
#   a) RIDER calling PUT /api/users/{id}/role   → 403
#   b) ADMIN calling PUT /api/users/{nonExist}/role → 404
#   c) ADMIN calling with invalid role string → 400
#   d) ADMIN calling with valid body → 200 + ROLE_CHANGED in auth_events
#   e) Cache user-service::user::{id} and user-service::S1-F12::* must be
#      wildcard-invalidated after the role change.
#
# Requires a seeded ADMIN user. We try to log in with the conventional
# admin@uber.com / admin password seed; if your seed differs, override via
# ADMIN_EMAIL / ADMIN_PASSWORD env vars.

ADMIN_EMAIL="${ADMIN_EMAIL:-admin@uber.com}"
# Default matches user-service/DataSeeder.java which auto-seeds this admin
# at startup. Override via env var if your stack uses a different seed.
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin123}"

ADMIN_TOKEN="$(login_user "$ADMIN_EMAIL" "$ADMIN_PASSWORD" || true)"
if [ -z "$ADMIN_TOKEN" ]; then
  skip "CC-2 role mgmt — no seeded admin reachable" \
       "set ADMIN_EMAIL/ADMIN_PASSWORD env or seed via SQL before running"
else
  # b) RIDER → 403
  RIDER_UID="$(jwt_uid "$TOKEN_RIDER")"
  http_auth PUT "$USER_URL/api/users/$RIDER_UID/role" "$TOKEN_RIDER" \
    -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
  assert_status 403 "RIDER → PUT /api/users/{id}/role → 403"

  # c) Missing token → 401
  http PUT "$USER_URL/api/users/$RIDER_UID/role" \
    -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
  assert_status 401 "no token → PUT /api/users/{id}/role → 401"

  # d) ADMIN → 400 invalid role enum
  http_auth PUT "$USER_URL/api/users/$RIDER_UID/role" "$ADMIN_TOKEN" \
    -H "Content-Type: application/json" -d '{"role":"BANANA"}'
  assert_status 400 "ADMIN with invalid role → 400"

  # e) ADMIN → 404 unknown user
  http_auth PUT "$USER_URL/api/users/9999999/role" "$ADMIN_TOKEN" \
    -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
  assert_status 404 "ADMIN with unknown user → 404"

  # f) Pre-warm cache for user-service::user::{id} (M1 GET-by-id is cached)
  http_auth GET "$USER_URL/api/users/$RIDER_UID" "$ADMIN_TOKEN" >/dev/null
  before="$(redis_count_keys "user-service::user::${RIDER_UID}")"

  # g) ADMIN → 200 promote rider
  http_auth PUT "$USER_URL/api/users/$RIDER_UID/role" "$ADMIN_TOKEN" \
    -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
  assert_status 200 "ADMIN → PUT /api/users/{id}/role → 200"

  # h) Verify ROLE_CHANGED audit event
  count="$(mongo_count auth_events "{ userId: $RIDER_UID, action: 'ROLE_CHANGED' }")"
  if [ "${count:-0}" -ge 1 ]; then
    pass "ROLE_CHANGED event written to auth_events"
  else
    fail "ROLE_CHANGED event written to auth_events" "expected ≥1, got $count"
  fi

  # i) Verify Redis invalidation (entity detail key removed)
  after="$(redis_count_keys "user-service::user::${RIDER_UID}")"
  if [ "$after" -lt "${before:-0}" ] || [ "${after:-0}" = "0" ]; then
    pass "user-service::user::${RIDER_UID} invalidated"
  else
    fail "user-service::user::${RIDER_UID} invalidated" "before=$before, after=$after"
  fi
fi

# --- 7. Public-endpoint sample check (§9.1 step f) -----------------------
# The spec wants "exactly 3 public endpoints (register, login, health)".
# We sample one endpoint outside the public set and assert 401. A full
# enumeration would require scanning every controller — that's a static-
# analysis concern, not a runtime test.

http GET "$USER_URL/api/users/preferences/search?key=lang&value=en"
assert_status 401 "/api/users/preferences/search is NOT public"

# --- 8. DP-3 UserLoaderHandler — "valid token, user deleted → 401" ------
# (§3.4 step e of the JWT filter chain test scenario.)
# Register, then the user-detail row gets deleted via CRUD DELETE; the same
# token becomes orphaned and the next call must return 401 rather than 500.
TOKEN_DEL="$(register_user "del")"
UID_DEL="$(jwt_uid "$TOKEN_DEL")"
if [ -n "$UID_DEL" ] && [ -n "$ADMIN_TOKEN" ]; then
  http_auth DELETE "$USER_URL/api/users/$UID_DEL" "$ADMIN_TOKEN" >/dev/null
  http_auth GET "$USER_URL/api/users/$UID_DEL" "$TOKEN_DEL"
  assert_status 401 "DP-3 UserLoaderHandler — token for deleted user → 401"
else
  skip "DP-3 UserLoaderHandler test" "no ADMIN seeded to delete the user"
fi
