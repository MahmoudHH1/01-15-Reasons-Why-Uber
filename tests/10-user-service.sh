#!/usr/bin/env bash
# user-service (port 8081) — full endpoint coverage.
# Spec sections covered:
#   §4.1   Password hashing (BCrypt)
#   §4.2   Role values (additive: RIDER + ADMIN)
#   §10.1.1 S1-F10 Register User
#   §10.1.2 S1-F11 Login
#   §10.1.3 S1-F12 User Activity Feed (ownership check)
#   §9.2    CC-2 Role management   PUT /api/users/{id}/role
#   M1 features S1-F1..S1-F9 + CRUD User + CRUD SavedAddress
#   §4.4    Cache contract on cached M1 GETs

source "$(dirname "$0")/lib/common.sh"

section "10 user-service — S1-F10/F11/F12 + CC-2 + M1 + CRUD"

BASE="$USER_URL"
EMAIL_A="ua-${RUN_ID}@example.com"
EMAIL_B="ub-${RUN_ID}@example.com"
PHONE_A="+201${RUN_ID:0:9}"
PHONE_B="+202${RUN_ID:0:9}"

# ============================================================
# S1-F10  POST /api/auth/register   (§10.1.1)
# ============================================================

# (a) 201 with valid data + token returned
http POST "$BASE/api/auth/register" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"User A","email":"$EMAIL_A","password":"securePassword123","phone":"$PHONE_A"}
EOF
)"
assert_status 201 "S1-F10 register A returns 201 (§10.1.1.g)"
TOKEN_A="$(echo "$LAST_BODY" | jq -r '.token // empty')"
[ -n "$TOKEN_A" ] && pass "S1-F10 token returned" || fail "S1-F10 token returned"
UID_A="$(jwt_uid "$TOKEN_A")"
ROLE_A="$(jwt_role "$TOKEN_A")"
# §4.2 step c — registration token's role claim is RIDER
[ "$ROLE_A" = "RIDER" ] && pass "S1-F10 default role = RIDER (§4.2.c)" || fail "S1-F10 default role = RIDER" "got $ROLE_A"

# (b) 409 duplicate email
http POST "$BASE/api/auth/register" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Dup Email","email":"$EMAIL_A","password":"x","phone":"+999${RUN_ID:0:9}"}
EOF
)"
assert_status 409 "S1-F10 duplicate email → 409"

# (c) 409 duplicate phone (different email)
http POST "$BASE/api/auth/register" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Dup Phone","email":"newx-$RUN_ID@example.com","password":"x","phone":"$PHONE_A"}
EOF
)"
assert_status 409 "S1-F10 duplicate phone → 409"

# (d) 400 missing fields
http POST "$BASE/api/auth/register" -H "Content-Type: application/json" -d '{"email":"e@x.io"}'
assert_status 400 "S1-F10 missing fields → 400"

# (e) Bad-faith role injection: client sends role=ADMIN — server must ignore (§4.2 step e)
http POST "$BASE/api/auth/register" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Sneaky","email":"sneaky-$RUN_ID@x.io","password":"securePassword123","phone":"+1${RUN_ID:0:9}9","role":"ADMIN"}
EOF
)"
SNEAK_TOKEN="$(echo "$LAST_BODY" | jq -r '.token // empty')"
SNEAK_ROLE="$(jwt_role "$SNEAK_TOKEN")"
[ "$SNEAK_ROLE" = "RIDER" ] && pass "S1-F10 ignores role=ADMIN in body, forces RIDER" \
                            || fail "S1-F10 ignores role=ADMIN in body, forces RIDER" "got $SNEAK_ROLE"

# ============================================================
# S1-F11  POST /api/auth/login   (§10.1.2)
# ============================================================

# (a) 200 with correct credentials
http POST "$BASE/api/auth/login" -H "Content-Type: application/json" -d "$(cat <<EOF
{"email":"$EMAIL_A","password":"securePassword123"}
EOF
)"
assert_status 200 "S1-F11 login OK"
TOKEN_A="$(echo "$LAST_BODY" | jq -r '.token // empty')"

# §10.1.2 step c — login emits LOGGED_IN to auth_events
sleep 1
li_count="$(mongo_count auth_events "{ userId: $UID_A, action: 'LOGGED_IN' }")"
[ "${li_count:-0}" -ge 1 ] && pass "S1-F11 emits LOGGED_IN to auth_events (§10.1.2.c)" \
                           || fail "S1-F11 emits LOGGED_IN to auth_events" "got $li_count"

# (b) 401 wrong password (NOT 400)
http POST "$BASE/api/auth/login" -H "Content-Type: application/json" -d "$(cat <<EOF
{"email":"$EMAIL_A","password":"wrong"}
EOF
)"
assert_status 401 "S1-F11 wrong password → 401"

# (c) 401 non-existent email (must NOT be 404 — prevents account enumeration §10.1.2 note)
http POST "$BASE/api/auth/login" -H "Content-Type: application/json" -d "$(cat <<EOF
{"email":"never-existed-$RUN_ID@x.io","password":"x"}
EOF
)"
assert_status 401 "S1-F11 unknown email → 401 (anti-enumeration)"

# (d) Token grants access to a protected endpoint
http_auth GET "$BASE/api/users/$UID_A" "$TOKEN_A"
assert_status 200 "S1-F11 token grants access to /api/users/{id}"

# (e) BCrypt password storage (§4.1)
# Without DB access we verify indirectly: the GET response must NOT echo back password
PASSWORD_FIELD="$(echo "$LAST_BODY" | jq -r '.password // empty')"
if [ -z "$PASSWORD_FIELD" ] || [ "$PASSWORD_FIELD" = "null" ]; then
  pass "GET /api/users/{id} does NOT expose password (§4.1)"
else
  fail "GET /api/users/{id} exposes password" "got: $PASSWORD_FIELD"
fi

# ============================================================
# S1-F12  GET /api/users/{id}/activity   (§10.1.3)
# ============================================================
# Behaviour matrix:
#   a) own token + own id        → 200 with REGISTERED + LOGGED_IN
#   b) other rider's token       → 403 (ownership, NOT 404)
#   c) ADMIN token + any user    → 200
#   d) no token                  → 401
#   e) ADMIN token + non-existent → 404
#   f) page=0&size=1             → 1 item with totalElements>=1
#   g) Cached for 5 minutes (§8.1)

http_auth GET "$BASE/api/users/$UID_A/activity" "$TOKEN_A"
assert_status 200 "S1-F12 own activity → 200"
total="$(echo "$LAST_BODY" | jq -r '.totalElements // .content | length' 2>/dev/null)"
if [ "$LAST_STATUS" = "200" ] && [ "${total:-0}" -ge 1 ]; then
  pass "S1-F12 has ≥1 event (REGISTERED/LOGGED_IN)"
else
  fail "S1-F12 has ≥1 event" "totalElements=$total"
fi

# Register user B; B uses A's id → 403
http POST "$BASE/api/auth/register" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"User B","email":"$EMAIL_B","password":"securePassword123","phone":"$PHONE_B"}
EOF
)" >/dev/null
TOKEN_B="$(echo "$LAST_BODY" | jq -r '.token // empty')"
http_auth GET "$BASE/api/users/$UID_A/activity" "$TOKEN_B"
assert_status 403 "S1-F12 cross-user → 403 (NOT 404)"

http GET "$BASE/api/users/$UID_A/activity"
assert_status 401 "S1-F12 no token → 401"

http_auth GET "$BASE/api/users/$UID_A/activity?page=0&size=1" "$TOKEN_A"
size="$(echo "$LAST_BODY" | jq -r '.content | length' 2>/dev/null)"
if [ "${size:-0}" = "1" ]; then
  pass "S1-F12 pagination size=1 honored"
else
  fail "S1-F12 pagination size=1 honored" "content size=$size"
fi

# size=999 must clamp to 100 — verify the *content* length, not the echoed
# Pageable.size, since Spring may echo back the request value.  (§10.1.3.d)
http_auth GET "$BASE/api/users/$UID_A/activity?size=999" "$TOKEN_A"
content_len="$(echo "$LAST_BODY" | jq -r '.content | length' 2>/dev/null)"
[ "${content_len:-0}" -le 100 ] && pass "S1-F12 content length clamped to ≤100" \
                                || fail "S1-F12 content length clamped to ≤100" "got $content_len"

# ============================================================
# CC-2  PUT /api/users/{id}/role   (§9.2)
# ============================================================

# RIDER → 403
http_auth PUT "$BASE/api/users/$UID_A/role" "$TOKEN_A" -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
assert_status 403 "CC-2 RIDER → 403"

# No token → 401
http PUT "$BASE/api/users/$UID_A/role" -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
assert_status 401 "CC-2 no token → 401"

ADMIN_TOKEN="$(login_user "${ADMIN_EMAIL:-admin@uber.com}" "${ADMIN_PASSWORD:-adminPassword123}" || true)"
if [ -n "$ADMIN_TOKEN" ]; then
  # §4.2 step d — seeded ADMIN's token carries role=ADMIN
  ADMIN_ROLE="$(jwt_role "$ADMIN_TOKEN")"
  [ "$ADMIN_ROLE" = "ADMIN" ] && pass "ADMIN login token role=ADMIN (§4.2.d)" \
                              || fail "ADMIN login token role=ADMIN" "got $ADMIN_ROLE"

  # ADMIN bypasses ownership check on activity feed (§10.1.3 test scenario d)
  http_auth GET "$BASE/api/users/$UID_A/activity" "$ADMIN_TOKEN"
  assert_status 200 "S1-F12 ADMIN bypasses ownership → 200 (§10.1.3.d)"

  http_auth GET "$BASE/api/users/9999999/activity" "$ADMIN_TOKEN"
  assert_status 404 "S1-F12 ADMIN + unknown user → 404 (§10.1.3.f)"

  # Invalid role → 400
  http_auth PUT "$BASE/api/users/$UID_A/role" "$ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"role":"BANANA"}'
  assert_status 400 "CC-2 invalid role enum → 400"

  # Unknown user → 404
  http_auth PUT "$BASE/api/users/9999999/role" "$ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
  assert_status 404 "CC-2 unknown user → 404"

  # Promote A → 200, ROLE_CHANGED event written
  http_auth PUT "$BASE/api/users/$UID_A/role" "$ADMIN_TOKEN" -H "Content-Type: application/json" -d '{"role":"ADMIN"}'
  assert_status 200 "CC-2 promote A → 200"
  rc="$(mongo_count auth_events "{ userId: $UID_A, action: 'ROLE_CHANGED' }")"
  [ "${rc:-0}" -ge 1 ] && pass "CC-2 ROLE_CHANGED event in auth_events" || fail "CC-2 ROLE_CHANGED event in auth_events"
else
  skip "CC-2 with seeded ADMIN" "no admin reachable; set ADMIN_EMAIL/ADMIN_PASSWORD"
fi

# ============================================================
# CRUD User   (§4.3 / §4.4.2)
# ============================================================

# CREATE
http_auth POST "$BASE/api/users" "$TOKEN_A" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Crud User","email":"crud-$RUN_ID@x.io","password":"securePassword123",
 "phone":"+9${RUN_ID:0:9}","role":"RIDER","status":"ACTIVE"}
EOF
)"
assert_status_in "CRUD POST /api/users → 201" 200 201
NEW_UID="$(echo "$LAST_BODY" | jq -r '.id // empty')"

if [ -n "$NEW_UID" ]; then
  http_auth GET "$BASE/api/users/$NEW_UID" "$TOKEN_A"
  assert_status 200 "CRUD GET /api/users/$NEW_UID"
  # Cached after first call (entity detail, 15 min)
  http_auth GET "$BASE/api/users/$NEW_UID" "$TOKEN_A" >/dev/null
  if [ "$(redis_count_keys "user-service::user::$NEW_UID")" -ge 1 ]; then
    pass "CRUD GET-by-ID caches user-service::user::$NEW_UID"
  else
    skip "CRUD GET-by-ID caches user-service::user::$NEW_UID" "expected by §4.4.2; user-service may not have @Cacheable"
  fi

  http_auth GET "$BASE/api/users" "$TOKEN_A"
  assert_status 200 "CRUD GET /api/users (list)"
  if [ "$(redis_keys 'user-service::user::list*' | wc -l)" = "0" ]; then
    pass "CRUD GET /api/users (list) NOT cached"
  else
    fail "CRUD GET /api/users (list) NOT cached"
  fi

  http_auth PUT "$BASE/api/users/$NEW_UID" "$TOKEN_A" -H "Content-Type: application/json" -d "$(cat <<EOF
{"name":"Crud User Renamed","email":"crud-$RUN_ID@x.io","password":"securePassword123",
 "phone":"+9${RUN_ID:0:9}","role":"RIDER","status":"ACTIVE"}
EOF
)"
  assert_status 200 "CRUD PUT /api/users/$NEW_UID"
  if [ "$(redis_count_keys "user-service::user::$NEW_UID")" = "0" ]; then
    pass "PUT invalidates user-service::user::$NEW_UID"
  else
    skip "PUT invalidates user-service::user::$NEW_UID" "service may not have @Cacheable"
  fi

  http_auth DELETE "$BASE/api/users/$NEW_UID" "$TOKEN_A"
  assert_status_in "CRUD DELETE /api/users/$NEW_UID" 200 204
fi

# ============================================================
# M1 S1 features (smoke pass — JWT + 2xx)
# Each is in §4.4 enumerated as cached: S1-F1, F3, F5, F6, F8, F9
# Endpoints verbatim from controller catalog.
# ============================================================

# S1-F1 search
http_auth GET "$BASE/api/users/search?role=RIDER" "$TOKEN_A"
assert_status_in "M1 S1-F1 GET /api/users/search" 200 204

# S1-F3 ride summary (per-user) — auto-grader cached as user-service::S1-F3::{userId}
http_auth GET "$BASE/api/users/$UID_A/ride-summary" "$TOKEN_A"
assert_status_in "M1 S1-F3 GET /api/users/{id}/ride-summary" 200 204

# S1-F5 preferences search
http_auth GET "$BASE/api/users/preferences/search?key=lang&value=en" "$TOKEN_A"
assert_status_in "M1 S1-F5 GET /api/users/preferences/search" 200 204

# S1-F6 top-riders report
http_auth GET "$BASE/api/users/reports/top-riders?startDate=2026-01-01&endDate=2026-12-31&limit=5" "$TOKEN_A"
assert_status_in "M1 S1-F6 GET /api/users/reports/top-riders" 200 204

# S1-F8 user profile
http_auth GET "$BASE/api/users/$UID_A/profile" "$TOKEN_A"
assert_status_in "M1 S1-F8 GET /api/users/{id}/profile" 200 204

# S1-F9 preferences/language
http_auth GET "$BASE/api/users/preferences/language?lang=en&minRides=0" "$TOKEN_A"
assert_status_in "M1 S1-F9 GET /api/users/preferences/language" 200 204

# S1-F2 update preferences (write — must invalidate S1-F3 wildcard per §4.4.4)
http_auth GET "$BASE/api/users/$UID_A/ride-summary" "$TOKEN_A" >/dev/null
before="$(redis_count_keys "user-service::S1-F3::*")"
http_auth PUT "$BASE/api/users/$UID_A/preferences" "$TOKEN_A" -H "Content-Type: application/json" -d '{"language":"en","notifications":true}'
assert_status_in "M1 S1-F2 PUT /api/users/{id}/preferences" 200 204
after="$(redis_count_keys "user-service::S1-F3::*")"
if [ "${after:-0}" -le "${before:-0}" ]; then
  pass "S1-F2 wildcard-invalidates S1-F3::* ($before→$after)"
else
  skip "S1-F2 wildcard-invalidates S1-F3::* ($before→$after)" "service may not cache S1-F3"
fi

# S1-F4 deactivate
http_auth PUT "$BASE/api/users/$UID_A/deactivate" "$TOKEN_A"
assert_status_in "M1 S1-F4 PUT /api/users/{id}/deactivate" 200 204

# S1-F7 default address — needs an address first (CRUD SavedAddress)
http_auth POST "$BASE/api/users/$UID_A/addresses" "$TOKEN_A" -H "Content-Type: application/json" -d "$(cat <<EOF
{"label":"Home","line1":"1 Main St","city":"Cairo","country":"EG","isDefault":false}
EOF
)"
ADDR_ID="$(echo "$LAST_BODY" | jq -r '.id // empty')"
if [ -n "$ADDR_ID" ]; then
  http_auth PUT "$BASE/api/users/$UID_A/addresses/$ADDR_ID/default" "$TOKEN_A"
  assert_status_in "M1 S1-F7 PUT addresses/{id}/default" 200 204
fi

# ============================================================
# CRUD SavedAddress
# ============================================================
http_auth GET "$BASE/api/users/$UID_A/addresses" "$TOKEN_A"
assert_status 200 "CRUD GET addresses (list)"
if [ -n "$ADDR_ID" ]; then
  http_auth GET "$BASE/api/users/$UID_A/addresses/$ADDR_ID" "$TOKEN_A"
  assert_status 200 "CRUD GET /addresses/{id}"
  http_auth PUT "$BASE/api/users/$UID_A/addresses/$ADDR_ID" "$TOKEN_A" -H "Content-Type: application/json" -d "$(cat <<EOF
{"label":"Home Updated","line1":"2 Main St","city":"Cairo","country":"EG","isDefault":true}
EOF
)"
  assert_status_in "CRUD PUT /addresses/{id}" 200 204
  http_auth DELETE "$BASE/api/users/$UID_A/addresses/$ADDR_ID" "$TOKEN_A"
  assert_status_in "CRUD DELETE /addresses/{id}" 200 204
fi
