---
name: jwt-bootstrap
description: Wire JWT authentication per service — JwtConfigurationManager singleton, JwtService bean, JwtAuthenticationFilter with the AuthHandler chain (Chain of Responsibility), per-service SecurityConfig, and the AuthController on user-service only. Lands as multiple commits on a `feat/cc/CC-1-jwt-<service>/<id>` branch.
---

# JWT Bootstrap

You are wiring JWT authentication for one service per `Uber_descriptionM2.pdf` §5 (Authentication & Authorization), §3.4 (CoR pattern), and §3.6 (Singleton pattern). The auto-grader inspects this code via reflection AND source-scan, so structure matters as much as behavior.

## Sources of Truth (Read First)

- **`docs/m2/design-patterns.md`** — DP-3 (Chain of Responsibility) and DP-5 (Singleton) sections list the exact structural rules and grader hooks this skill must satisfy. Read both before writing any code.
- **`docs/m2/yaml-fragments/<service>.application.yml`** — `jwt:` block reference (secret + expiration shape).
- **`docs/m2/event-actions.md`** — for the user-service `AuthController`: `REGISTERED`, `LOGGED_IN`, `ROLE_CHANGED` action vocabulary and constraints.
- **`Uber_descriptionM2.pdf`** §3.4, §3.6, §5 — spec text. Use `pdf-clause-finder` for verbatim clauses.

If the doc and this skill disagree, trust the doc.

## Step 1: Identity + Service

Confirm developer name + ID. Ask which service is being wired (or pick "all 5" — but the workflow is one service per branch).

```
git checkout main && git pull origin main
git checkout -b feat/cc/CC-1-jwt-<service>/<studentId>
```

## Step 2: Pom Dependency

Add JJWT (any 0.12.x release works with Spring Boot 4 / JDK 25):

```xml
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
```

Spring Security comes via `spring-boot-starter-security`:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

## Step 3: JwtConfigurationManager (Singleton — DP-5)

§3.6 hard requirements:

- **Private constructor**.
- Public **`static getInstance()`** with thread-safe init (DCL or eager).
- Single `private static` field holds the instance.
- **NOT** annotated with `@Component`, `@Service`, `@Configuration`, or any Spring stereotype.
- Reads `JWT_SECRET` and `JWT_EXPIRATION_MS` env vars with sensible defaults (Docker Compose sets these). Singleton-bridge from a Spring `@Configuration` is also acceptable.

Place at `com.team01.uber.<service>.security.JwtConfigurationManager`.

```java
package com.team01.uber.<service>.security;

public final class JwtConfigurationManager {
    private static volatile JwtConfigurationManager instance;
    private final String secret;
    private final long expirationMs;

    private JwtConfigurationManager() {
        String s = System.getenv("JWT_SECRET");
        this.secret = (s == null || s.isBlank()) ? "<dev-fallback-base64-32-bytes>" : s;
        String e = System.getenv("JWT_EXPIRATION_MS");
        this.expirationMs = (e == null || e.isBlank()) ? 86400000L : Long.parseLong(e);
    }

    public static JwtConfigurationManager getInstance() {
        if (instance == null) {
            synchronized (JwtConfigurationManager.class) {
                if (instance == null) instance = new JwtConfigurationManager();
            }
        }
        return instance;
    }

    public String getSecret() { return secret; }
    public long getExpirationMs() { return expirationMs; }
}
```

Reflection sanity to commit alongside:

- `getInstance() == getInstance()` (reference equality).
- 10 parallel threads call `getInstance()` and all return the same reference.
- Class has exactly one constructor declared `private`.
- Class has no `@Component/@Service/@Configuration` annotation.

## Step 4: JwtService (Spring bean)

A normal `@Service` that **does not** read `application.yml` directly — it pulls config from `JwtConfigurationManager.getInstance()`. This is the bridge that wires Singleton to the Spring world.

```java
@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMs;

    public JwtService() {
        var cfg = JwtConfigurationManager.getInstance();
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(cfg.getSecret()));
        this.expirationMs = cfg.getExpirationMs();
    }

    public String issueToken(String email, Long uid, String role) {
        return Jwts.builder()
            .subject(email)
            .claim("uid", uid)
            .claim("role", role)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact();
    }

    public Claims verifyAndParse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
```

Token claims (§5.2):

- `sub` = email.
- `uid` = User.id (Long, custom claim).
- `role` = role string.
- `iat`, `exp` standard.

Algorithm = HMAC-SHA256. Secret ≥ 32 bytes when Base64-decoded. Header format = `Authorization: Bearer <token>`.

## Step 5: AuthHandler Chain (Chain of Responsibility — DP-3)

§3.4 hard requirements:

- Abstract `AuthHandler` (or interface) with **both** `setNext(AuthHandler)` and `handle(AuthContext)` methods.
- ≥ 3 concrete handlers — recommended 4: `TokenExtractionHandler` (401 if missing), `SignatureValidationHandler` (401 if invalid/expired), `UserLoaderHandler` (401 if user not in PG), `RoleAuthorizationHandler` (403 if insufficient role).
- Each handler does its job, on failure short-circuits with the right status, on success calls `next.handle(ctx)`.

```java
public abstract class AuthHandler {
    private AuthHandler next;
    public AuthHandler setNext(AuthHandler n) { this.next = n; return n; }
    public abstract AuthResult handle(AuthContext ctx);
    protected AuthResult passToNext(AuthContext ctx) {
        return next == null ? AuthResult.success(ctx) : next.handle(ctx);
    }
}

public class AuthContext {
    private final HttpServletRequest request;
    private String token;
    private Claims claims;
    private User user;
    private String requiredRole;
    // getters/setters
}
```

Concrete handlers each extend `AuthHandler`. Place in `security.handler` package.

## Step 6: JwtAuthenticationFilter

§3.4 critical: Spring's SecurityFilterChain is itself a chain of responsibility. **Do NOT replace it** — your custom AuthHandler chain runs **inside** `JwtAuthenticationFilter.doFilterInternal()`.

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserRepository userRepository;   // for UserLoaderHandler (user-service); other services may load via PG too
    // constructor injection

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (isPublic(req)) { chain.doFilter(req, res); return; }

        AuthHandler head = new TokenExtractionHandler();
        head.setNext(new SignatureValidationHandler(jwtService))
            .setNext(new UserLoaderHandler(userRepository))
            .setNext(new RoleAuthorizationHandler(/* required role for this endpoint, if any */));

        AuthResult result = head.handle(new AuthContext(req));
        if (!result.isSuccess()) {
            res.setStatus(result.status());   // 401 or 403
            return;   // short-circuit — do NOT call chain.doFilter
        }

        SecurityContextHolder.getContext().setAuthentication(result.toAuthentication());
        chain.doFilter(req, res);
    }
}
```

Grader inspects this method body to confirm the AuthHandler chain is invoked from inside the filter rather than duplicating extraction/validation/authorization logic inline.

## Step 7: Per-Service SecurityConfig

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter filter;

    @Bean
    public SecurityFilterChain http(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(publicEndpoints()).permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private RequestMatcher[] publicEndpoints() {
        return new RequestMatcher[] {
            new AntPathRequestMatcher("/api/auth/register", "POST"),   // user-service only
            new AntPathRequestMatcher("/api/auth/login", "POST"),      // user-service only
            new AntPathRequestMatcher("/api/<service>/health"),        // health
        };
    }

    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
}
```

§5.4 mandates: stateless sessions, CSRF disabled, public-only `register`/`login` (user-service) and health checks.

## Step 8: AuthController (user-service ONLY)

§10.1.1, §10.1.2:

- `POST /api/auth/register` — public; validate name/email/password/phone non-blank (400), email/phone unique (409), BCrypt hash, role=RIDER, status=ACTIVE, log REGISTERED to `auth_events`, return token + expiresIn 201.
- `POST /api/auth/login` — public; 401 on user-not-found OR wrong-password (no 404 — anti-enumeration), log LOGGED_IN, return token + expiresIn 200.

Also wire the role-management endpoint (CC-2): `PUT /api/users/{id}/role`. ADMIN-only (handled by `RoleAuthorizationHandler`); 404 on user-not-found, 400 on invalid role enum, log ROLE_CHANGED to `auth_events`, invalidate `user-service::user::{id}` and `user-service::S1-F12::*`, return 200.

## Step 9: Test Plan (commit a small integration test or run live)

- Public endpoints (`POST /api/auth/register`, `POST /api/auth/login`, health) → succeed without `Authorization` header.
- Any other M1 endpoint without token → 401.
- With `Authorization: Bearer abc` (malformed) → 401.
- With expired token → 401 (test with `JWT_EXPIRATION_MS=1` in env).
- `PUT /api/users/{id}/role` with RIDER token → 403.
- `PUT /api/users/{id}/role` with ADMIN token → 200.
- `GET /api/users/{id}` after deleting that user from PG, with the deleted user's still-valid token → 401 (UserLoaderHandler caught it).
- `JwtConfigurationManager.getInstance() == JwtConfigurationManager.getInstance()` → true.
- Source scan: `JwtConfigurationManager` class file has no `@Component/@Service/@Configuration`.
- Source scan: `JwtAuthenticationFilter.doFilterInternal` constructs/invokes the AuthHandler chain (no inline extract/validate/authorize logic).

## Step 10: Hand Off

Push and PR. Tell the user: "JWT wiring landed for `<service>`. The next step is `observer-bootstrap` to wire MongoEventLogger so the `REGISTERED`/`LOGGED_IN`/`ROLE_CHANGED` events emitted by AuthController actually persist."
