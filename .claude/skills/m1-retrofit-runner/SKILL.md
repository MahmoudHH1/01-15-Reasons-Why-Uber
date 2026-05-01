---
name: m1-retrofit-runner
description: Guided one-time pass through Section 4 of the M2 PDF — applies the prerequisite M1 retrofits (BCrypt, JWT filter, Redis caching, Observer chain, surgeFee, description key, driver auto-index, simulateFailure) before any M2 feature work begins. Each retrofit lands as its own short branch.
---

# M1 Retrofit Runner

You are walking the team through the M1 modifications required before any M2 feature can be built (Uber_descriptionM2.pdf §4). Each retrofit is its own branch and PR — they are NOT one big change. The retrofits have ordering dependencies, so this skill enforces sequence.

The single source of truth is `Uber_descriptionM2.pdf` §4 (and the entity sections referenced from there). Read the PDF, do not work from memory.

## Sources of Truth (Read First)

The retrofits map onto specific docs — consult them as you do each one rather than relying on this skill's summary:

- **`docs/m2/cache-matrix.md`** — for MOD-4 (Redis caching enumeration of 27 + 10 endpoints, plus the 48 invalidating paths).
- **`docs/m2/event-actions.md`** — for MOD-5a (Observer retrofit) and MOD-5e (Factory). Tells you exactly which actions to emit on which M1 writes.
- **`docs/m2/design-patterns.md`** — for MOD-5b/c/d (Builder, Adapter, Singleton retrofits) and the grader hooks for each.
- **`docs/m2/yaml-fragments/<service>.application.yml`** — for MOD-CC6 (yml migration starting templates).
- **`Uber_descriptionM2.pdf`** §4 — the authoritative retrofit spec. Use `pdf-clause-finder` for verbatim clauses.

If the doc and this skill disagree, trust the doc.

## Step 1: Verify Identity

Confirm developer name + student ID against `team.json` / CLAUDE.md. Echo back. STOP if not a real team member. Use the verified student ID for every branch and commit in the session.

## Step 2: Inventory — What Already Lands

Run a fast read-only scan to figure out which retrofits are already done so we don't redo them:

| Retrofit | Detection signal |
|---|---|
| MOD-1 BCrypt | grep `BCryptPasswordEncoder` in user-service; check that stored password starts with `$2` (run a single `psql` query if user wants live check) |
| MOD-2 Role enum + ADMIN seed | grep enum `Role` for `ADMIN`; check seed/import sql |
| MOD-3 JWT filter | look for `JwtAuthenticationFilter` (or equivalent) in every service's `config/`/`security/` package |
| MOD-4 Redis cache | grep `RedisTemplate`, `@Cacheable`, or a custom `CacheService` in each service |
| MOD-5a Observer chain | grep `EntityObserver`, `MongoEventLogger` |
| MOD-5b Builder retrofits | per the M1-DTO list in §3.5 |
| MOD-5c Adapter retrofits | grep `ObjectArrayDtoAdapter` (only required if M1 used `Object[]` projections) |
| MOD-5d Singleton-bridge | grep `JwtConfigurationManager`; assert no `@Component`/`@Service`/`@Configuration` annotation on it |
| MOD-5e Factory | grep `EventFactory` |
| MOD-6 surgeFee | grep `surgeFee` in payment-service S5-F4 path |
| MOD-7 description key | grep `description` in driver-service create/update paths |
| MOD-8 simulateFailure | grep `simulateFailure` query param handling in S5-F4 |
| MOD-9 Driver CRUD auto-index | look for JPA entity listener (`@PostPersist/@PostUpdate/@PostRemove`) on Driver, OR a service-level hook that calls ES on every CRUD |

Print a pass/skip table before proposing any work.

## Step 3: Pick the Next Retrofit

The order matters. Propose them in this sequence (ask the user to confirm before each):

1. **MOD-CC6 — application.yml migration** (CC-6). Migrate every `application.properties` → `application.yml` per §6.5. This is the foundation; do it first because every later retrofit edits config.
2. **MOD-CC5 — docker-compose six-DB stack** (§6.4). Add mongo, redis, elasticsearch, neo4j, cassandra services with the **exact image tags and memory caps** §6.4 lists. Pin postgres to `:17`. (PG18 breaks Hibernate 7.2 — explicitly called out in §1.)
3. **MOD-1 — BCrypt password hashing** (§4.1). Hash on register; hide `password` in every User-returning DTO; re-seed users with hashed values.
4. **MOD-2 — Role enum + seeded ADMIN** (§4.2). Additive: keep `RIDER` and `ADMIN`; default on register = `RIDER`; ignore `role` in register payloads; seed ≥1 ADMIN.
5. **MOD-5d — JwtConfigurationManager singleton** (§3.6). Private constructor, public static `getInstance()`, NOT a Spring bean. Reads `JWT_SECRET` / `JWT_EXPIRATION_MS` env vars (fallback defaults) OR use the singleton-bridge pattern. Run reflection-style sanity (`getInstance() == getInstance()`).
6. **MOD-3 — JWT filter chain on every endpoint** (§4.3 + §3.4). Build CoR chain inside `JwtAuthenticationFilter.doFilterInternal()` with `TokenExtractionHandler → SignatureValidationHandler → UserLoaderHandler → RoleAuthorizationHandler`. Public exceptions: `POST /api/auth/register`, `POST /api/auth/login`, health checks. Add the User Service `AuthController` (register/login).
7. **MOD-5a — Observer chain + MongoEventLogger** (§3.3 + §4.5). Each of the 5 services owns its own `MongoEventLogger` bound to a fixed EventType. Mongo failures `log.warn` and do NOT roll back the PG tx.
8. **MOD-5e — EventFactory** (§3.7). Common `MongoEvent` interface; `EventFactory.createEvent(EventType, Map<String, Object>)`. Source-scan: no `new AuthEvent(...)` etc. anywhere outside the factory.
9. **MOD-5b — Builder on M1 DTOs** (§3.5). Apply to S1-F3/F6/F8/F9, S2-F3/F6/F9, S3-F3/F6/F9, S4-F3/F6/F8/F9, S5-F3/F6/F8/F9. Skip S2-F8 and S3-F8 (return entities, not DTOs).
10. **MOD-5c — Adapter for `Object[]` SQL projections** (§3.8). Required for S1-F3 (explicitly mandates `Object[]`). Conditional for any other M1 feature that chose `Object[]`.
11. **MOD-4 — Redis caching on M1 reads** (§4.4). Use the explicit enumeration: 27 cached M1 features + 10 CRUD GET-by-ID = 37 endpoints. Implement wildcard invalidation per §4.4.6. **List endpoints not cached**.
12. **MOD-6 — surgeFee in transactionDetails** (§4.6). M1 S5-F4 must write `transactionDetails.surgeFee` on Payment creation. Source: `Ride.metadata.surgeMultiplier` (`baseFare * (multiplier - 1)`) or 15% of total. Pre-M2 rows tolerate missing key (readers default to `0.15 * amount`).
13. **MOD-7 — Driver.vehicleDetails.description default** (§7.2 / §4.7 checklist). Existing rows tolerated as empty string.
14. **MOD-8 — simulateFailure on S5-F4** (§4.5 tail). Optional `?simulateFailure=true` short-circuits to `Payment.status=FAILED` and writes a `FAILED` audit event via the Observer chain.
15. **MOD-9 — Driver CRUD auto-index to ES** (§4.5 tail). JPA entity listener (`@PostPersist/@PostUpdate/@PostRemove`) on the Driver entity, OR a service-level hook. Do NOT inline ES calls in every controller.

## Step 4: Execute the Selected Retrofit

For each selected retrofit:

### 4a. Branch

```
git checkout main && git pull origin main
git checkout -b feat/m1/MOD-<n>/<studentId>
```

For CC-prefixed retrofits use `feat/cc/CC-<n>/<studentId>` instead.

### 4b. Read the PDF Section

Use `pdf-clause-finder` (or `Read` with `pages`) to extract the exact §4.x or §3.x clause. Do NOT work from memory.

### 4c. Plan in Commits

Each retrofit should be 2–4 small commits. Examples:

- **MOD-3 (JWT)**: (1) JwtConfigurationManager singleton, (2) JwtService, (3) AuthHandler chain classes, (4) JwtAuthenticationFilter wiring + SecurityConfig per service.
- **MOD-4 (Redis)**: (1) RedisTemplate config + cache key helpers, (2) cached annotations on the 27 reads, (3) invalidation matrix on the 18+30 writes, (4) wildcard SCAN+DEL helper.
- **MOD-5a (Observer)**: (1) `EntityObserver` interface + `MongoEventLogger` class, (2) abstract Subject mixin, (3) wire one M1 write end-to-end as the template, (4) wire the rest.

### 4d. Commit Convention

Use the M2 expanded format:

```
feat(<service-name>): <description> (<studentId>)
```

For DP-spanning commits, cite the DP ID:

```
feat(payment-service): wire Strategy selector for refund (implements DP-1) (55-25376)
```

Cross-cutting work uses `cc` scope:

```
feat(cc): CC-5 add elasticsearch + neo4j + cassandra to compose (55-25085)
```

### 4e. Test the Retrofit

After each retrofit, run the **test scenario from the PDF section** (every §4.x clause has one). Examples:

- MOD-1: `POST /api/auth/register` → check stored hash starts `$2`, login with same creds works, GET /api/users/{id} hides `password`.
- MOD-3: every M1 endpoint returns 401 without token, 200 with valid token, health/register/login still public.
- MOD-4: hit cached endpoint twice → second-call latency < first; redis-cli shows the key; trigger a write → key is gone.
- MOD-5a: trigger any M1 write → matching MongoDB collection has a document; unregister observers in a unit test → no document is written.
- MOD-9: POST a Driver via CRUD → ES has the document without calling /index; DELETE → ES document is gone.

Report PASS/FAIL per step. Fix as a follow-up commit.

### 4f. Push & PR

The user pushes and opens the PR. Tell them to use a regular merge commit and **not delete** the branch.

## Step 5: Cross-Service Coordination

Some retrofits (MOD-3, MOD-4, MOD-5a, MOD-5e) need to land in every service. Encourage the team to:

- Have one owner per retrofit propose the template in their own service first.
- Other services copy the template once it's reviewed.
- Each service still gets its own branch (e.g., `feat/m1/MOD-3-driver/<id>`, `feat/m1/MOD-3-ride/<id>`) so each member has attributable commits — auto-grader requires per-member commits.

## Step 6: Verification After All Retrofits

When the user thinks all retrofits are done, run a final pass equivalent to §4.7 Summary Checklist:

- [ ] BCrypt applied to registration and seed data
- [ ] ADMIN role present, RIDER preserved as default
- [ ] At least one ADMIN user seeded
- [ ] All endpoints (except register/login/health) require JWT
- [ ] 37 GET endpoints cached (27 M1 features + 10 CRUD GET-by-ID)
- [ ] M1 write endpoints invalidate the right detail/feature caches
- [ ] M1 write endpoints notify observers
- [ ] M1 complex DTOs (F3/F6/F8/F9) use Builder pattern
- [ ] M1 `Object[]` native SQL results (F3/F6/F9) use Adapter pattern
- [ ] M1 Driver rows have `vehicleDetails.description` (default empty)
- [ ] M1 S5-F4 writes `surgeFee` to `Payment.transactionDetails`
- [ ] M1 S5-F4 supports `?simulateFailure=true`
- [ ] M1 Driver CRUD auto-syncs to Elasticsearch
- [ ] Every service has `application.yml` (no `application.properties`)
- [ ] Six-DB Docker Compose boots cleanly under 5 GB

If everything passes, the team is unblocked for M2 features. Tell the user: "Run the `m2-feature-developer` skill to start an F10–F12 feature."
