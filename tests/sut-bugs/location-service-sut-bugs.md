# location-service — SUT bugs

## §9.1 / §3.4 — JwtAuthenticationFilter swallows handler errors as 500 instead of 401
**Test:** `tests/40-location-service.sh:83` — `assert_status 201 "S4-F11 POST /api/locations/$DID/tracking → 201"`
**Spec quote:**
> §9.1 CC-1 — JWT on All Endpoints (p. 30): "Every endpoint (including your M1 endpoints) must require a valid JWT token in the Authorization header, except POST /api/auth/register, POST /api/auth/login, and health checks. Missing or invalid token returns **401**. Insufficient role returns **403**."
> §3.4 Chain of Responsibility — JWT Filter Chain (p. 9): "Concrete handlers (in order): TokenExtractionHandler (401 if missing), SignatureValidationHandler (401 if invalid/expired), UserLoaderHandler (401 if user not found in PG), RoleAuthorizationHandler (403 if insufficient role for the endpoint) … If any handler in your chain fails, write the appropriate status code (401 or 403) to the response and short-circuit by **not** calling filterChain.doFilter(...)."
**Observed:** Every authenticated POST/GET hitting `/api/locations/...` returns HTTP 500 with body `Authentication processing error`. The filter wraps the entire chain dispatch in a `try { ... } catch (Exception e) { response.setStatus(SC_INTERNAL_SERVER_ERROR); response.getWriter().write("Authentication processing error"); }`. Any exception that bubbles out of a handler (or out of `filterChain.doFilter` while the auth context is being built) is silently re-mapped to 500 instead of letting the handler's 401/403 stand or letting Spring's normal error pipeline run.
**Expected per spec:** Authentication failures must return **401** (missing/invalid token, user not found) or **403** (insufficient role). 500 is never a legal auth outcome. The chain must short-circuit cleanly without a blanket `catch (Exception) → 500`.
**Likely location:** `location-service/src/main/java/com/team01/uber/location/security/JwtAuthenticationFilter.java` lines 48-64 (the `try { head.handle(ctx) … } catch (Exception e) { 500 + "Authentication processing error" }` block).

### Cascading failures from the same root cause
The following four assertions in `tests/40-location-service.sh` also fail with HTTP 500 / `Authentication processing error` because the request never reaches the service layer:

- Line 86 — `S4-F11 emits TRACKING_RECORDED` (Mongo audit never written because the POST was 500'd).
- Line 105 — `S4-F11 partial body (no heading/accuracy/rideId) → 201`.
- Line 138 — `S4-F12 with time range` (assert 200/204 — got 500).
- Line 259 — `S4-F10 ANALYTICS_VIEWED on every call (+$diff)` — `last status=500` on the GETs at lines 254-255 means the dashboard handler never ran and the Observer never logged ANALYTICS_VIEWED.

All four resolve once the underlying auth filter is fixed; they are not separate bugs.
