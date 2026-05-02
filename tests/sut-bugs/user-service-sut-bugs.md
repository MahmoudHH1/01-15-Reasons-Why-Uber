# user-service — SUT bugs found by the test suite

Each entry maps a failing assertion in `tests/10-user-service.sh` to a verbatim
spec citation. SUT code lives under `user-service/src/main/...` and must be
fixed by the user-service team — the test suite does not modify SUT sources.

## §5.5 — User entity leaks `password` field in JSON responses
**Test:** `tests/10-user-service.sh:110` — `fail "GET /api/users/{id} exposes password"`
**Spec quote:**
> §5.5 Password Handling — "The plaintext password is **never** stored or returned in API responses"
> (Uber_descriptionM2.pdf, p. 22)

The clause says "never returned in API responses". The current `GET /api/users/{id}` response includes the BCrypt hash:
```
"password":"$2a$10$dLPNxBaDthaj72WNHpn0ou/hZtp/MB12pxknsDeig5CfS.xcA8Y9a"
```
Even though the value is hashed (not plaintext), exposing the hash externally still violates the "never returned" rule — the password column should not appear in any HTTP response body, hashed or otherwise. Returning the hash also enables offline brute-force against weak passwords.

**Observed:** `GET /api/users/{id}` returns the full User entity with the `password` field populated by the BCrypt hash.

**Expected per spec:** The serialized User payload must not contain a `password` key at all on any read endpoint (`GET /api/users/{id}`, `GET /api/users`, search results, profile, ride-summary, etc.).

**Likely location:** `user-service/src/main/java/com/team01/uber/user/model/User.java` — the `password` field is missing `@com.fasterxml.jackson.annotation.JsonIgnore` (or `@JsonProperty(access = WRITE_ONLY)`). Add either annotation on the field. Registration/login still work because Jackson can deserialize on the way in via the request DTO (`RegisterRequest`/`LoginRequest`) — `User.password` is only used for the entity-side write, never echoed back. After the fix, the test on line 107 will see an empty `.password` field and pass.

## §4.4.2 — `GET /api/users/{id}` not cached (CRUD entity-detail caching missing)
**Test:** `tests/10-user-service.sh:223-227` — `fail "CRUD GET-by-ID caches user-service::user::$NEW_UID (§4.4.2 + §8.1 entity detail 15m)"`
**Spec quote:**
> §4.4.2 CRUD Baseline Endpoints That Must Be Cached — "For every entity, **only the get-by-ID endpoint is cached**. List endpoints are not cached — they hit PostgreSQL on every request. … Uber entities (10): user, saved-address, driver, driver-document, ride, ride-stop, location, payment, coupon, payment-coupon. **10 GET-by-ID endpoints must be cached**."
> (Uber_descriptionM2.pdf §4.4.2, p. 16)
> §8.1 TTL Guidelines — "Entity detail views: **15 minutes**. Examples: Driver profile, ride details."
> (Uber_descriptionM2.pdf §8.1, p. 30)

**Observed:** After `POST /api/users` (creates a fresh row) followed by `GET /api/users/{id}` twice, no Redis key matching `user-service::user::{id}` exists. The two sibling services (driver-service `driver-service::driver::{id}`, location-service `location-service::location::{id}`) both populate this key correctly on identical CRUD GET-by-ID calls — only user-service is missing the `@Cacheable` decoration on `getUserById`.

**Expected per spec:** `GET /api/users/{id}` must populate Redis key `user-service::user::{id}` with TTL 15 min. PUT/DELETE on the same id must clear that key (§4.4.6 wildcard invalidation rule). Because §4.4.2 is the canonical CRUD baseline, the auto-grader counts this as a graded retrofit (CC-3 §9.3.a explicitly enumerates "for each of the 27 cached M1 feature endpoints and 10 CRUD GET-by-ID endpoints: confirm a key with the expected `<service>::<featureId>::<param-hash>` or `<service>::<entity>::<id>` format exists").

**Likely location:** `user-service/src/main/java/com/team01/uber/user/service/UserService.java` — the `getUserById(Long id)` method needs `@Cacheable(value = "user-service::user", key = "#id")` and the corresponding `@CacheEvict` (or wildcard `cacheInvalidator.deleteKey("user-service::user::" + id)`) on `updateUser`, `deleteUser`, and any S1-Fx write that mutates the user row. The wildcard helper to invalidate `user-service::S1-F12::*` already exists in the codebase (or can be modeled after the driver-service `CacheInvalidationService`); the pattern just needs to extend to the user entity-detail key. Once added, the test on line 224 will see the key and pass; the matching invalidation assertion on line 247 (`PUT invalidates user-service::user::{id}`) will pass automatically.
