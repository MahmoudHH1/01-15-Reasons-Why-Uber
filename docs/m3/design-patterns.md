<!-- Loaded by: skills/pr-check, skills/m3-orchestrator -->

# M3 Design Patterns — Locations & Grader Hooks

Source of truth: M2 spec §3 + §4.5 (M1 retrofits). **All 7 M2 design patterns carry over to M3 unchanged** per `docs/m3/uber-m3.md` line 40. The auto-grader uses **reflection** (class structure assertions) **and** **source/class scans** (greps and class-file inspection) — both must pass.

Distribution: 3 Creational (Builder, Singleton, Factory), 1 Structural (Adapter), 3 Behavioral (Strategy, Observer, Chain of Responsibility).

---

## DP-1 Strategy — S5-F12 Refund Logic

**Where applied:** payment-service, S5-F12 only. Not retrofitted to any M1 method.

**Required structure:**
- `RefundStrategy` interface with exactly one abstract method: `calculateRefund(Payment payment, RefundRequest request) → RefundResult` (amount + reason code).
- 3 concrete strategies:
  - `FullRefundWithSurgeStrategy` — selected when `refundSurge=true` AND payment within 24-hour window. Returns full payment amount.
  - `BaseFareOnlyRefundStrategy` — selected when `refundSurge=false` AND within window. Returns `payment.amount − transactionDetails.surgeFee`.
  - `NoRefundStrategy` — selected when payment older than 24 hours from `createdAt`. Returns 0 with reason "refund window expired".
- `RefundStrategySelector` (or `RefundStrategyFactory`) — separate class with a `select(payment, request)` method that returns `RefundStrategy`.

**Service contract:** `paymentService.refundSurgeAdjusted(...)` calls `selector.select(payment, request).calculateRefund(payment, request)` and **never** contains `if (refundSurge)` branching — the grader greps for it.

**Grader hooks:**
- Reflection: `RefundStrategy` is an interface with exactly one abstract method named `calculateRefund`. The 3 concrete classes implement it.
- Reflection: A class named `RefundStrategySelector` (or `RefundStrategyFactory`) exists with a select-like method returning `RefundStrategy`.
- Source-scan: payment-service has no `if (refundSurge)` (or equivalent boolean branching).
- **Source-scan: NO class named `Strategy` or `RefundStrategy*` exists outside `payment-service`** — graded explicitly.
- Behavioral: refund-surge-adjusted call within 24h with `refundSurge=true` → audit trail records `FullRefundWithSurgeStrategy`; same with `refundSurge=false` → `BaseFareOnlyRefundStrategy`; payment >24h → 400 + `NoRefundStrategy`.

---

## DP-2 Observer — Event Logging

**Where applied:** all 5 services. M1 write endpoints retrofit to fire observers.

**Required structure:**
- `EntityObserver` interface with `void onEvent(String eventType, Object payload)`.
- `MongoEventLogger` concrete class implementing `EntityObserver`. Bound to a fixed `EventType` per service at construction (user→AUTH, driver→DRIVER, ride→RIDE, location→LOCATION, payment→PAYMENT_AUDIT). Each service owns its own logger; registration is **not** shared across services.
- Subject side: `register(observer) / unregister(observer) / notifyObservers(eventType, payload)` methods on each subject (typically a Service class with a base/mixin).

**Failure policy:** `MongoEventLogger` catches Mongo exceptions, calls `log.warn(...)`, **does not rethrow**. The upstream Postgres transaction must NOT roll back on Mongo write failure (soft dependency, §6.3).

**Composition with Factory:** action string → `params["action"]` → `EventFactory.createEvent(boundEventType, params)` → save via Spring Data Mongo. The action string is NOT the EventType.

**Where M2 features fire observers:**
- S1 register/login/role-change → `auth_events`
- S2 driver updates → `driver_events`
- S3 ride state changes → `ride_events`
- S4 location GPS events → `location_events`
- S5 refund processed → `payment_audit_trail`
- All M1 writes also retrofit to fire (§4.5 retrofit table row 2).

**Grader hooks:**
- Reflection: `EntityObserver` interface exists with `onEvent(String, Object)`.
- Reflection: `MongoEventLogger` class exists implementing `EntityObserver`.
- Source/class-file scan: no method in any of the 5 services annotated `@EventListener` writes to MongoDB. All Mongo writes flow through the classical Observer chain.
- Behavioral: register a fresh user via `POST /api/auth/register` → exactly one `REGISTERED` document in `auth_events`. Login as that user → `LOGGED_IN` added. Trigger an M1 write (e.g., S1-F2 update preferences) → corresponding event in `auth_events`. Unregister observers in a unit test and repeat → no Mongo document appears.

---

## DP-3 Chain of Responsibility — JWT Filter Chain

**Where applied:** all 5 services (JWT filter on every M1 + M2 endpoint).

**Required structure:**
- Abstract `AuthHandler` with `setNext(AuthHandler) → AuthHandler` and `handle(AuthContext) → AuthResult`.
- `AuthContext` carries the HTTP request, extracted token, authenticated user, required role.
- ≥3 concrete handlers (recommended 4): `TokenExtractionHandler` (401 if missing), `SignatureValidationHandler` (401 if invalid/expired), `UserLoaderHandler` (401 if user not in PG), `RoleAuthorizationHandler` (403 if insufficient role).
- Each handler does its job and either short-circuits with the right status or passes to next.

**Spring Security integration (critical):** Spring's `SecurityFilterChain` IS itself a chain of responsibility. **Do NOT replace it.** Build the AuthHandler chain **inside** `JwtAuthenticationFilter.doFilterInternal()`:

1. If endpoint is public, `chain.doFilter(...)` and return.
2. Construct/inject the `AuthHandler` chain head and call `head.handle(new AuthContext(request))`.
3. On failure, set the response status (401/403) and short-circuit (do NOT call `chain.doFilter`).
4. On success, populate `SecurityContextHolder` and call `chain.doFilter(...)`.

**Grader hooks:**
- Reflection: `AuthHandler` exists with `setNext(AuthHandler)` and `handle(...)`.
- Reflection: at least 3 concrete subclasses exist (`TokenExtractionHandler`, `SignatureValidationHandler`, `UserLoaderHandler`, optionally `RoleAuthorizationHandler`).
- Source-scan: `JwtAuthenticationFilter.doFilterInternal()` body invokes the AuthHandler chain (not duplicating extraction/validation/authorization logic inline).
- Behavioral: protected endpoint without `Authorization` → 401 (extraction failed); with `Bearer invalid.token` → 401 (signature failed); valid token but user deleted → 401 (loader failed); RIDER calling ADMIN-only `PUT /api/users/{id}/role` → 403 (authorization failed); ADMIN calling same → 200.

---

## DP-4 Builder — Dashboard / Analytics DTOs

**Where applied:** M2 dashboard DTOs + M1 retrofit DTOs with 5+ fields.

**Required structure:**
- Static inner `Builder` class on each DTO (or external `<DtoName>Builder` for records).
- Static `builder()` method to start construction.
- Fluent setters returning `this`.
- `build()` method returning the DTO instance.

**Java records caveat:** Records aren't naturally Builder-friendly. Two acceptable approaches:
1. Convert the record to a class with a static inner `Builder`.
2. Keep the record and write an external `<DtoName>Builder` whose `build()` invokes the canonical record constructor.

**M2 DTOs that must have Builder:**
- `DriverDashboardDTO` (S2-F12): driverId, name, totalRides, totalEarnings, averageRideFare, averageRating, totalRatings.
- `RideAnalyticsDashboardDTO` (S3-F10): totalRides, totalRevenue, averageRideFare, completionRate, ridesByStatus.
- `LocationAnalyticsDTO` (S4-F10): totalLocationEvents, activeDrivers, averageSpeed, eventsByHour.
- `VehicleTypeRevenueDTO` (S5-F10): vehicleType, baseFareRevenue, surgeFeeRevenue, totalRevenue, rideCount.

**M1 retrofit scope (DTOs with 5+ fields):**
- S1-F3, S1-F6, S1-F8, S1-F9
- S2-F3, S2-F6, S2-F9
- S3-F3, S3-F6, S3-F9
- S4-F3, S4-F6, S4-F8, S4-F9
- S5-F3, S5-F6, S5-F8, S5-F9

**S2-F8 and S3-F8 do NOT use Builder** — they return entities (Driver, Ride), not DTOs.

**Grader hooks:**
- Reflection: every DTO returned by an M2 dashboard feature has accessible static `builder()`, fluent setters return Builder, `build()` returns the DTO type.
- Reflection: every in-scope M1 DTO (`UserRideSummaryDTO`, `TopRiderDTO`, `DriverEarningsDTO`, etc.) has a Builder.
- Behavioral: integration test on S2-F12 returns a correctly-populated dashboard.
- Compile-time/static check: confirm S2-F8 and S3-F8 do NOT use Builder.

---

## DP-5 Singleton — JwtConfigurationManager

**Where applied:** holds shared, immutable JWT config (secret, expiration, algorithm).

**Required structure:**
- Private constructor.
- `public static getInstance()` with thread-safe initialization (double-checked locking or eager init).
- Single `private static` field holds the instance.
- **NOT** annotated with `@Component`, `@Service`, `@Configuration`, or any other Spring stereotype.

**Loading config (non-Spring class):**
- **Recommended:** read `JWT_SECRET` and `JWT_EXPIRATION_MS` env vars in the private constructor with sensible fallback defaults. Docker Compose already sets these.
- **Alternative — singleton-bridge:** a Spring `@Configuration` reads `application.yml` via `@Value`, then pushes values into the singleton via a static setter (e.g., `JwtConfigurationManager.initConfig(secret, expirationMs)`) during `@PostConstruct`.

**Spring vs classical GoF:** Spring `@Service` / `@Component` beans are singletons by default but managed by the Spring container, not by the GoF pattern. M2 requires **exactly one** classical GoF Singleton: `JwtConfigurationManager`. `JwtService` remains a Spring `@Service` and obtains JWT config via `JwtConfigurationManager.getInstance()` (not via `@Autowired`).

**Grader hooks:**
- Reflection: `JwtConfigurationManager` exists with exactly one declared constructor with `private` access.
- Reflection: `getInstance()` is a `public static` method returning `JwtConfigurationManager`.
- Reference equality: two calls to `getInstance()` return the same reference (`ref1 == ref2`, not `.equals()`).
- Thread-safety: 10 parallel threads each call `getInstance()` → all return the same reference.
- Class scan: NOT annotated with any Spring stereotype.
- Integration: `JwtService` (a Spring bean) reads JWT config via `JwtConfigurationManager.getInstance()` and successfully issues + validates a token.

---

## DP-6 Factory — Mongo Event Creation

**Where applied:** event construction in all 5 services.

**Required structure:**
- Common `MongoEvent` interface implemented by all 5 concrete event classes (`AuthEvent`, `DriverEvent`, `RideEvent`, `LocationEvent`, `PaymentAuditEvent`). Methods: `getId() → String`, `getTimestamp() → LocalDateTime`, `getAction() → String`, `getDetails() → Map<String, Object>`.
- `EventFactory` class with `createEvent(EventType type, Map<String, Object> params) → MongoEvent`.
- `EventType` enum with values `AUTH, DRIVER, RIDE, LOCATION, PAYMENT_AUDIT`. The factory dispatches on this enum.

**Composition with Observer:** in the write flow (M1 write completes → `notifyObservers("USER_UPDATED", payload)` → `MongoEventLogger.onEvent` → `params.put("action", "USER_UPDATED")` → `EventFactory.createEvent(EventType.AUTH, params)` → save).

**Grader hooks:**
- Reflection: `MongoEvent` interface exists with the four methods. All 5 concrete event classes implement it.
- Reflection: `EventFactory.createEvent(EventType, Map<String, Object>)` exists.
- Behavioral: `EventFactory.createEvent(AUTH, params)` → returned object is assignable to `AuthEvent` and its fields match the params. Repeat for each EventType.
- Behavioral: `EventFactory.createEvent(PAYMENT_AUDIT, ...)` → result exposes `method` and `amount` (service-specific fields on top of the common interface).
- Integration: register a user → resulting `auth_events` document type and fields match what `EventFactory.createEvent(AUTH, ...)` would produce (proves services go through the factory).
- Source-scan: no service class contains `new AuthEvent(...)` / `new DriverEvent(...)` / etc. anywhere outside `EventFactory.java`.

---

## DP-7 Adapter — NoSQL Result → DTO

**Where applied:** read paths converting NoSQL results (Mongo Document, ES SearchHit, Neo4j Record, Cassandra Row) to service DTOs.

**Required structure:**
- One adapter class per NoSQL source per service:
  - `MongoDocumentAdapter` — all 5 services
  - `ElasticsearchHitAdapter` — driver-service only
  - `Neo4jRecordAdapter` — ride-service only
  - `CassandraRowAdapter` — location-service only
- Each adapter has a single `adapt(source) → targetDto` method.
- **No universal Entity-Dto base type** — each adapter converts to its specific domain DTO.

**M1 retrofit scope (conditional):** if an M1 feature uses `Object[]` row projections from native SQL (S1-F3 explicitly mandates this), wrap the mapping in an `ObjectArrayDtoAdapter` rather than inlining mapping in the service. Features that use JPQL constructor expressions or DTO projections are **exempt** — those satisfy the pattern by construction.

**Grader hooks:**
- Reflection: for each NoSQL source the service uses, the matching adapter class exists.
- Reflection: each adapter has an `adapt(...)` method whose return type matches the service's domain DTO.
- Behavioral: pass a mock Mongo `Document` to `MongoDocumentAdapter.adapt(...)` → returned DTO has fields populated from the document's keys.
- Behavioral (driver-service): pass an ES `SearchHit` to `ElasticsearchHitAdapter.adapt(...)` → returned driver DTO is correct.
- Behavioral (S1-F3 specifically): native SQL `Object[]` rows are converted to `UserRideSummaryDTO` via `ObjectArrayDtoAdapter` (not inline).
- Behavioral (any in-scope M1 F3/F6/F9 with `Object[]`): adapter exists.

---

## Cross-Pattern Composition Workflow (§4.5)

For Observer + Factory + Adapter on a write path:

1. M1 write endpoint completes its PG update.
2. Service calls `notifyObservers("ACTION_STRING", payload)`. The first arg is the **action string**, NOT the EventType.
3. `MongoEventLogger.onEvent(actionString, payload)` runs. The logger is bound to a fixed EventType at construction.
4. Logger builds the factory input: copies the action string into `params.put("action", actionString)`, plus the rest of the payload, then calls `EventFactory.createEvent(boundEventType, params)`.
5. Factory returns the matching concrete `MongoEvent` (e.g., `AuthEvent`) typed through the common interface.
6. Logger persists via Spring Data's repository. On Mongo failure, `log.warn` and do not rethrow.

Adapter sits separately on **read paths** (NoSQL query results → DTO). It does NOT participate in the write-side composition above.

---

## Grading Summary (§3.9)

Each pattern contributes roughly equally to the design-patterns portion of the grade. Grader does:

1. **Static analysis** — reflective inspection of class structure (interfaces, methods, constructors, annotations).
2. **Source/class scans** — grep-style checks for forbidden constructions (e.g., `new AuthEvent(...)` outside factory, `if (refundSurge)` in payment service, `Strategy` named classes outside payment-service, `@EventListener` writing to Mongo).
3. **Behavioral verification** — integration tests that exercise the patterns indirectly through M2 features (and M1 retrofitted endpoints).

Run `pr-check` (or equivalently the `pattern-verifier`-style checks inside it) before opening any PR that touches a pattern location.
