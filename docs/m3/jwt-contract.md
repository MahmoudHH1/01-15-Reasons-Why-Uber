<!-- Loaded by: skills/gateway-bootstrap, skills/pr-check, skills/m3-orchestrator -->

# M3 JWT Contract

Two-layer JWT enforcement in M3:

1. **Primary validator** — the api-gateway runs a reactive `GlobalFilter` that validates every incoming request (except `/api/auth/**` and the 5 health checks). Per Critical Rule #8 (uber-m3.md:2642): "**JWT validation at gateway.** Individual services retain their M2 JWT filter for defense-in-depth, but the gateway is the public-facing validator."
2. **Defense-in-depth** — each service still has its M2 servlet `JwtAuthenticationFilter` registered in `SecurityConfig`. This catches the case where a request reaches a service through some path other than the gateway (e.g., a misconfigured cluster or a future internal-only test).

## JWT payload

- Algorithm: **HMAC-SHA256**.
- Secret: ≥32 bytes (256 bits) when Base64-decoded. Short readable strings throw `WeakKeyException`.
- **Same secret across all 5 services + gateway** — user-service issues, gateway + every service verify against the same secret. Held in K8s `jwt-secret` (uber-m3.md:1773), injected as env var into every pod.
- Payload claims:
  - `sub` — user email
  - `uid` — `User.id` (Long, custom claim — used by ownership checks)
  - `role` — role string (`USER`, `DRIVER`, `ADMIN`)
  - `iat`, `exp`
- Header: `Authorization: Bearer <token>`.
- Expiration: 24 hours (`86400000` ms).

## Public endpoints (no JWT required)

- `POST /api/auth/register` (user-service only)
- `POST /api/auth/login` (user-service only)
- `GET /api/<svc>/health` × 5

Any other endpoint exposed without auth = grading failure. The gateway's `/api/auth/**` bypass covers register and login.

## Gateway filter (reactive, M3-new — uber-m3.md §9.3)

The M2 servlet-based filter cannot be copy-pasted. The reactive WebFlux differences (uber-m3.md:1469–1487):

1. **Class shape.** Implement `org.springframework.cloud.gateway.filter.GlobalFilter` instead of `OncePerRequestFilter`. Signature: `Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)`. No `HttpServletRequest`/`HttpServletResponse`, no `void` return, no `chain.doFilter(req, res)`.
2. **Filter ordering.** `@Component` + implement `Ordered` returning `-1` (or `@Order(-1)`) so the filter runs **before** Spring Cloud Gateway's route-resolution filter.
3. **Path bypass.** Read path via `exchange.getRequest().getPath().value()` (reactive). Bypass `/api/auth/**`. Other paths require valid `Authorization: Bearer <token>`.
4. **Header parsing & validation.** Read `Authorization` from `exchange.getRequest().getHeaders().getFirst("Authorization")`, strip `Bearer `, validate against env-injected `JWT_SECRET`. On failure: set status to 401, return `exchange.getResponse().setComplete()`.
5. **Header forwarding.** On success, mutate the downstream request to inject identity for the M2 service-side filters:
   ```java
   ServerHttpRequest mutated = exchange.getRequest().mutate()
       .header("X-User-Id", claims.get("uid", Long.class).toString())
       .header("X-User-Role", claims.get("role", String.class))
       .header("X-Correlation-ID", correlationId)
       .build();
   return chain.filter(exchange.mutate().request(mutated).build());
   ```

The gateway does **not** depend on the `contracts/` module and does **not** call any service via Feign — the validator is self-contained in `api-gateway/src/main/java/.../auth/JwtValidator.java` (uber-m3.md:1489).

## Service-side filter (M2 carry-over)

Each service still has:
- `JwtConfigurationManager` (Singleton, DP-5) — private constructor, `getInstance()`, **no Spring stereotype**.
- `JwtService` (Spring `@Service`) bridges into the singleton.
- `JwtAuthenticationFilter` (`OncePerRequestFilter`) registered in `SecurityConfig`.
- `AuthHandler` chain (DP-3, Chain of Responsibility) built **inside** `JwtAuthenticationFilter.doFilterInternal()`.
- `SecurityConfig` — stateless sessions, CSRF disabled, only public paths exempt.

The service-side filter reads either:
- The `X-User-Id` / `X-User-Role` headers forwarded by the gateway (preferred — saves a JJWT decode), OR
- The `Authorization: Bearer <token>` header (when the request reached the service via some non-gateway path).

When both are present, the gateway-forwarded headers win — the gateway already validated.

## Token staleness

Token staleness after role change is an accepted limitation — there is no revocation list. A demoted ADMIN keeps ADMIN privileges for up to 24h (until token expiry).

## Ownership checks (M3 §2.10)

For path-param endpoints (`/api/users/{id}/...`, `/api/drivers/{id}/...`, etc.):

> The path-param `{userId}` or `{driverId}` must equal the caller's `X-User-Id`, **OR** the caller's `X-User-Role` must equal `ADMIN`. Otherwise → throw **403 Forbidden**.

> Caller-existence rule: before any business logic, look up the caller (local query or Feign to user-service) — if not found, → **404 Not Found**.

This catches stale tokens whose backing user has been deleted/deactivated. The per-feature blocks in §3–§7 reference this rule with "**Auth:** ownership rule (§2.10)".

Aggregate report endpoints (S1-F6 top-riders, S2-F6 top-rated-drivers, S5-F1 revenue) require **role = ADMIN** (`Auth: ADMIN only`).
