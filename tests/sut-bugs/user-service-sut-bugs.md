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
