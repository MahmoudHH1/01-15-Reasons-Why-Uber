---
name: pr-check
description: Full pre-PR verification checklist. Run this before creating a pull request to catch all auto-grader-failing issues at once. Covers M1 baselines (git, layered architecture, CRUD, build), M2 carry-over invariants (JWT, caching, observers, design patterns, application.yml, AI-authorship trailers), and M3-new gates (per-service PG datasource, Feign clients in contracts/, RabbitMQ DLQ topology, idempotent consumers, gateway reactive filter, K8s manifests, observability artifacts).
---

# Pre-PR Checklist

You are running comprehensive verification before the user creates a PR. This catches every issue that would cause the auto-grader to deduct points or give zero credit. Run every section in order — do NOT skip checks. Each section runs unconditionally; sections that don't apply to this branch's scope return `N/A` but still appear in the report.

## CRITICAL — Authorship & Repository Boundaries (Zero Exceptions)

These rules override every other instruction in this skill.

- **Never reveal AI authorship.** No `Co-Authored-By`, `Generated-by`, "Made with Claude", "via Claude Code", `🤖`, or any similar trailer/tag/comment in commits, code, PRs, branch names, or file headers.
- **Never reference yourself.** Don't say "I implemented", "Claude added", "the assistant created" anywhere visible in commits or PR descriptions.
- **Never commit, push, merge, or open a PR directly.** This skill verifies the branch but does NOT push or open the PR. The user runs `git push` and `gh pr create` themselves.
- **Never `git config user.*` or alter Git identity.**
- **Stage and draft, don't act.** Show the user the exact commands to run; let them execute.

## Sources of Truth (Read Before Each Run)

If a check feels ambiguous, the doc is the tiebreaker:

- **`docs/m3/cache-matrix.md`** — Section 12 (caching hooks).
- **`docs/m3/event-actions.md`** — Section 13 (observer chain + action vocabulary + RabbitMQ routing keys).
- **`docs/m3/design-patterns.md`** — Section 14 (DP locations + grader hooks).
- **`docs/m3/yaml-fragments/<service>.application.yml`** — Section 10 (application.yml correctness).
- **`docs/m3/saga-events.md`** — Sections 19/20 (saga + idempotency).
- **`docs/m3/feign-contracts.md`** — Section 18 (Feign in `contracts/`).
- **`docs/m3/k8s-manifests.md`** — Section 22 (K8s artifacts).
- **`docs/m3/observability.md`** — Section 22 (Loki4J + actuator).
- **`docs/m3/jwt-contract.md`** — Section 21 (gateway reactive filter).
- **`docs/m3/m2-carryover.md`** — Section 15 (still-graded M2 behaviors).
- **`docs/m3/uber-m3.md`** — final M3 authority. Use `spec-clause-finder --milestone m3` for verbatim clauses.
- **`Uber_descriptionM2.pdf`** — final M2 authority for the carry-over invariants. Use `spec-clause-finder --milestone m2`.

If a doc disagrees with this skill, trust the doc and flag the drift to the user.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text during a PR check, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (the 11 `docs/m3/*.md` files listed above) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Step 0: Determine Branch Scope

Read the current branch name. Classify it (used for reporting, NOT for skipping checks):

- `<type>/M3/<ID>/<studentID>` → M3 minimal slice (e.g., `feat/M3/S5-INFRA/55-24853`).
- `<type>/M3/<scope>/<ID>/<studentID>` → M3 with scope (e.g., `feat/M3/payment/S5-F4/55-24853`).
- Cross-cutting scopes: `cc`, `infra`, `githooks`, `docker`, etc.
- `<type>` ∈ {feat, fix, bugfix, hotfix, release, docs, refactor, test, chore}.

Per uber-m3.md:2531 the M3 branch format is mandatory. The legacy M1/M2 format `<type>/<service>/<feature>/<id>` is **rejected** by `.githooks/post-checkout`.

> **Hook drift caveat:** if the active `.git/hooks/post-checkout` still enforces the M1/M2 format (this happens when `core.hooksPath` is unset and a stale hook is installed in `.git/hooks/`), the working format becomes `<type>/<scope>/<descriptor>/<id>` with `<scope>=cc` for cross-cutting work. Run `git config core.hooksPath .githooks` to install the canonical M3-aware hook from the repo. This is a one-time local fix, not a code change.

Print the resolved branch type at the top of the report.

## Step 1: Git Conventions

Check the current branch name and all commits on this branch (vs main):

- Branch matches the M3 §13.1 pattern: `^<type>/M3/<ID>/<studentID>$` or `^<type>/M3/<scope>/<ID>/<studentID>$`.
  - `<type>` ∈ {feat, fix, bugfix, hotfix, release, docs, refactor, test, chore}.
  - `M3` is literal — the milestone marker.
  - `<scope>` (optional middle segment): `user`, `driver`, `ride`, `location`, `payment` (per-service), or `cc`/`infra`/`githooks`/etc. (cross-cutting).
  - `<ID>`: stable identifier — `S<n>-<ID>` (e.g., `S5-F4`, `S5-INFRA`, `S3-F11`), or a short kebab-case slug.
  - `<studentID>`: always last; matches a member in `team.json`.
- Commit subjects match `<type>(<scope>): <imperative subject> (<studentID>)`. Keep ≤ 72 chars, no trailing period. When a commit implements a numbered design pattern, **cite the DP ID in the subject** (e.g., "implements DP-1 Strategy").
- Student ID is consistent across branch name and all commits.
- Student ID matches a member in `team.json`.
- The scope in the branch matches the member's assigned service (per-service work) or `cc`/`infra` (cross-cutting).
- Note: branch uses the service shortname (`driver`); commit subject uses the full service name (`driver-service`) when the scope is per-service.
- **Branch must NOT be `main`** — every change must come via PR ("Do not push directly to `main`" per CLAUDE.md).

## Step 2: Incremental Commits

Run `git log main..HEAD --oneline` and count commits.

- WARN if there is only 1 commit (features should have 3-5+ commits).
- Check that commits show a logical progression (repo → service → controller, or DTO → adapter → service → controller for M2).
- Where a design pattern is implemented across multiple branches, the commit subject should cite the DP ID (e.g., "implements DP-1 Strategy"). Note any DP-touching commit that's missing the citation.

## Step 3: Layered Architecture

Read all Java files in `controller/`, `service/`, `repository/`, and `model/` packages of the service being worked on. Check each against these rules:

**Controller Layer** (`controller/` package)
- Allowed: Call service methods, handle HTTP mapping, return responses, basic request validation.
- Forbidden: Importing or injecting Repository classes directly, any `@Query`/`@Transactional`/`@Modifying` annotations, business logic (calculations, conditional workflows, data transformations).

**Service Layer** (`service/` package)
- Allowed: Business logic, calling repository methods, data validation, orchestration.
- Forbidden: Importing `HttpServletRequest`, `@RequestMapping`, `@GetMapping`, `@PostMapping` etc., returning `ResponseEntity`, direct JDBC or EntityManager usage.

**Repository Layer** (`repository/` package)
- Allowed: `JpaRepository` interface extension, `@Query` annotations, method naming conventions, NoSQL repositories (`MongoRepository`, `ElasticsearchRepository`, etc.).
- Forbidden: Business logic in default/custom methods. Should be interfaces only (not classes).

**Cross-Service Rules (M3)**
- Forbidden: `@ManyToOne`, `@OneToMany`, `@ManyToMany` referencing entities from other services, importing entity classes from other service packages.
- Forbidden: native SQL `@Query` joining tables in another service's database. Per Critical Rule #1 (uber-m3.md:2635) — "**No cross-service JDBC.** Zero tolerance."
- Required: plain `Long` fields for cross-service FK columns (uber-m3.md:104).
- Required for cross-service reads: `@FeignClient` interfaces declared in the `contracts/` Maven module (uber-m3.md:2570).
- Required for cross-service writes: RabbitMQ events on a `<svc>.events` TopicExchange (uber-m3.md:2636).

**Relationship Rules**
- Required: `@JsonIgnore` on the inverse side (the `List<>`/`Set<>` side) of all bidirectional relationships.
- Intra-service `@ManyToOne` (e.g., `SavedAddress→User`, `DriverDocument→Driver`, `RideStop→Ride`, `PaymentCoupon→Payment/Coupon`) stay JPA-managed (uber-m3.md:117).

For each FAIL, report exactly which file and line violates which rule and suggest the fix.

## Step 4: Entity Compliance

For each entity in the service, verify it matches the spec:
- All fields present with correct types.
- Correct JPA annotations (`@Entity`, `@Id`, `@GeneratedValue`, relationships).
- No extra fields beyond the spec (warn, don't fail).
- For M2-additive JSONB keys: `Driver.vehicleDetails.description` (default empty string), `Payment.transactionDetails.surgeFee` (compute from surgeMultiplier or 15% fallback).
- **M3:** any `@ManyToOne` or `@JoinColumn` that pointed to another service's entity becomes a plain `Long` field (uber-m3.md:104). The DB column still exists; the JPA mapping is gone.
- **M3:** Ride entity has the new saga statuses in its enum: `PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`, `REFUNDED` (uber-m3.md:46–55). M1's `COMPLETED`, `CANCELLED`, `REQUESTED`, `ACCEPTED`, `IN_PROGRESS` stay unchanged.

## Step 5: CRUD Completeness

For each entity in the service, verify all 5 CRUD operations exist:
- **Create:** POST endpoint in controller, create method in service, save in repository.
- **Read by ID:** GET endpoint with path variable, findById in service.
- **Read all:** GET endpoint returning list, findAll in service.
- **Update:** PUT endpoint, update logic in service.
- **Delete:** DELETE endpoint, deleteById in service/repository.

## Step 5b: CRUD Conventions

Run the CRUD & Infrastructure Conventions check from the **verify-entity** skill (Section 6). Report as a sub-table within the PR check output.

## Step 6: Build Verification

Run `mvn clean package -DskipTests` from the project root and check for compilation errors.

## Step 7: Code Style

Scan for code style issues:
- Excessive comments (javadoc on every method, obvious comments).
- Auto-generated boilerplate comments.
- TODO/FIXME left in code.
- Hardcoded test data in main source files.

## Step 8: AI Authorship Hidden (CRITICAL)

This is a hard rule from CLAUDE.md. Two scans:

**Scan A — commit messages (no exclusions):**

```
git log main..HEAD --format=%B | grep -iE "co-authored-by|generated.with|claude code|anthropic|openai|chatgpt|copilot|made.with.claude"
```

Output MUST be empty. Commits should never carry these markers regardless of which files they touch.

**Scan B — added diff lines, scoped to non-tooling paths:**

The repo's `.claude/**`, `docs/m2/**`, `docs/m3/**`, and `.github/**` directories legitimately reference "Claude Code", "Anthropic", "Copilot", etc. by design (skill descriptions, agent prompts, doc references). A naïve `git diff` grep would false-fail every PR that touches those paths. Scope the scan to user-facing source files and only inspect added lines:

```
git diff main..HEAD -- ':!.claude/**' ':!docs/m2/**' ':!docs/m3/**' ':!.github/**' \
  | grep -E '^\+' \
  | grep -iE "co-authored-by|generated.with|claude code|anthropic|openai|chatgpt|copilot|made.with.claude"
```

Output MUST be empty.

**FAIL** if either scan matches — instruct the user to amend/rebase the commits to drop the trailer or remove the visible marker (or, if already pushed, force-push with the user's permission).

## Step 9: M3 Branch Conventions Sanity

The M3 §13.1 format is now mandatory (uber-m3.md:2531). Reaffirm here in case the Step 1 regex passed but the structure is suspicious:

- Cross-cutting branches use `cc`/`infra`/`githooks`/etc. scope, e.g., `chore/M3/cc/claude-suite-m3/<id>`.
- Per-service branches use the service shortname as scope, e.g., `feat/M3/payment/S5-F4/<id>`.
- Per-slice infra branches use the slice ID as the descriptor, e.g., `feat/M3/S5-INFRA/<id>` (uber-m3.md:2531).
- For DP-spanning commits, subject cites `DP-<n>` (M2 carry-over rule).

If branch is `chore/M3/<scope>/<descriptor>/<id>` for project tooling and the diff only touches `.claude/`, `docs/m3/`, `.githooks/`, `.github/`, etc., service-side checks (Steps 3, 4, 5, 11, 12, 13, 14, 17–22) are N/A.

## Step 10: application.yml Present + M3 blocks

For each service touched by this branch, confirm `src/main/resources/application.yml` exists and there is **no** `application.properties` left over. The grader explicitly checks for YAML.

```
find <service>/src/main/resources -name 'application.properties' -o -name 'application.yml'
```

If neither exists, FAIL. If `application.properties` exists, FAIL (must migrate per CC-6).

**M3-required blocks** (compare against `docs/m3/yaml-fragments/<service>.application.yml`):

- `spring.datasource.url` is `jdbc:postgresql://<svc>-postgres:5432/uberdb-<svc>s` (with env-var override). FAIL if it still says `postgres:5432/uberdb` (uber-m3.md:65–100).
- `spring.rabbitmq.*` block present with `acknowledge-mode: auto`, `default-requeue-rejected: false`, `max-attempts: 3` (uber-m3.md:262–275).
- `feign.<other-svc>.url` entries for every Feign client this service uses (uber-m3.md:193–205).
- `management.endpoints.web.exposure.include: "prometheus,health,info"` (uber-m3.md:1885).
- `management.metrics.distribution.percentiles-histogram.http.server.requests: true` (uber-m3.md:1888).
- `jwt.secret` present (env-var-driven; defense-in-depth filter still uses it).

If branch doesn't touch service config, N/A.

## Step 11: JWT Filter on New Endpoints (CC-1 + uber-m3.md:2642)

If the branch touches a controller, every new endpoint (other than `/api/auth/register`, `/api/auth/login`, health) must require auth.

- **Defense-in-depth (still required)**: the service has `JwtAuthenticationFilter` registered in `SecurityConfig`. The new controller does NOT use `@PermitAll` or `permitAll()` for its routes. Spot-test: call the new endpoint without an `Authorization` header → 401.
- **Primary validator (M3 — see Step 21)**: the gateway must also have a route entry for the path; the gateway's `JwtGatewayFilter` (reactive `GlobalFilter`) bypasses `/api/auth/**` and rejects any other path without a Bearer token.

For CC-2 (`PUT /api/users/{id}/role`): without ADMIN role → 403; with ADMIN → 200.

If branch doesn't touch any controller, N/A.

## Step 12: Caching Hooks (CC-3)

If the branch adds a read endpoint:
- It uses the cache key convention `<service>::S{n}-F{m}::<param-hash>` (or for entity detail `<service>::<entity>::<id>`).
- TTL matches `docs/m3/cache-matrix.md` (5 min search/activity/recommendations/tracking, 10 min dashboards/analytics, 15 min entity detail).
- For dashboards (S2-F12, S3-F10, S4-F10, S5-F10): the `*_VIEWED` event is logged on **every** invocation (including cache hits) — logging step runs **outside** the cache layer.

If the branch adds a write endpoint:
- It invalidates `<service>::<entity>::{id}` for the affected entity.
- It wildcard-deletes any `<service>::S{n}-F{m}::*` keys whose output may include the changed entity.
- Per `docs/m3/cache-matrix.md`, NoSQL-writer paths (S4-F11, S3-F11, S2-F11/CRUD auto-index, S5-F10/F11 audit-trail writes) must **also** invalidate the matching read caches even though no PG row mutates.
- **M3:** RabbitMQ consumers that mutate local state must also invalidate the matching read caches after the local commit (e.g., `ride.completed` consumer in driver-service flips driver to AVAILABLE → invalidate `driver-service::driver::{id}`).

If unsure, run the `cache-audit` skill on the affected endpoints. Otherwise, spot-test: call the write, verify the cached key is gone via `redis-cli`.

If branch doesn't touch caching, N/A.

## Step 13: Observer Chain (CC-4 / DP-2 / DP-6)

If the branch adds a write endpoint:

- The service emits the matching event via `notifyObservers(action, payload)`. Spot-test by tailing the matching MongoDB collection during a request.
- The action string is in the `docs/m3/event-actions.md` vocabulary (UPPER_SNAKE_CASE).
- Source-scan: no `new <Event>(...)` outside `EventFactory`.
   ```
   grep -rEn "new (AuthEvent|DriverEvent|RideEvent|LocationEvent|PaymentAuditEvent)\b" \
     --include='*.java' <service>/src/main/java/ | grep -v '/event/EventFactory.java'
   ```
   Must be empty.
- Source-scan: no `@EventListener` writes to Mongo.
   ```
   grep -rEln "@EventListener" --include='*.java' <service>/src/main/java/ \
     | xargs -r grep -ln "MongoTemplate\|MongoRepository"
   ```
   Must be empty.
- For payment-shaped actions (CREATED/COMPLETED/FAILED/REFUNDED/REFUND_DENIED/RETRY_ATTEMPTED), the event has `method` and `amount` set.
- For `ANALYTICS_VIEWED` and `DASHBOARD_VIEWED`: confirm the Observer chain does NOT trigger cache invalidation for these actions (they're observability-only).

If branch doesn't add a write endpoint, N/A.

## Step 14: Design Pattern Locations (CC-4)

Patterns must live in their assigned spots. Check based on what the branch touches (full reference: `docs/m3/design-patterns.md`):

| Pattern | Location | Check |
|---|---|---|
| Strategy (DP-1) | payment-service S5-F12 only | grep `RefundStrategy` or `Strategy` *outside* payment-service → must be empty (graded). Inside payment-service: `RefundStrategy` interface, 3 concrete strategies (`Full…`, `BaseFareOnly…`, `NoRefund…`), and a `RefundStrategySelector` (or `Factory`). Service contains NO `if (refundSurge)`. |
| Observer (DP-2) | all 5 services | covered by Step 13. |
| Chain of Responsibility (DP-3) | all 5 services | `AuthHandler` abstract/interface with `setNext` + `handle`; ≥3 concrete handlers; `JwtAuthenticationFilter.doFilterInternal()` body invokes the chain (not duplicating logic inline). |
| Builder (DP-4) | dashboard/analytics DTOs | every M2 dashboard DTO (`DriverDashboardDTO`, `RideAnalyticsDashboardDTO`, `LocationAnalyticsDTO`, `VehicleTypeRevenueDTO`) plus M1 retrofit DTOs (S1-F3/F6/F8/F9 etc.) has `builder()` static, fluent `setX()` returning `this`, and `build()` returning the DTO. S2-F8 / S3-F8 (entities, not DTOs) skip Builder. |
| Singleton (DP-5) | `JwtConfigurationManager` | private constructor only; `public static getInstance()`; NO `@Component`/`@Service`/`@Configuration`; reference equality holds. |
| Factory (DP-6) | event creation | covered by Step 13. |
| Adapter (DP-7) | NoSQL → DTO | One adapter per NoSQL source per service: `MongoDocumentAdapter` (×5), `ElasticsearchHitAdapter` (driver), `Neo4jRecordAdapter` (ride), `CassandraRowAdapter` (location). For S1-F3, `ObjectArrayDtoAdapter` exists and is used. |

If a DP is implemented across multiple branches, the commit subject must cite the DP ID. Verify on this branch.

If branch doesn't touch any DP location, N/A.

## Step 15: M2 Carry-Over Feature Behavior (full reference: `docs/m3/m2-carryover.md`)

Compare implemented behavior against the M2 §10.x spec when the diff touches an M2 feature (S1-F10..S5-F12). Spot-check:

- HTTP status codes match the spec **exactly** (e.g., S1-F11 returns 401 for both wrong-password AND user-not-found — NOT 404).
- Ownership checks for S1-F12 and S3-F12 use direct numeric equality on `uid` claim (or M3 `X-User-Id` header) vs path/query — no PG lookup.
- Required databases (per the feature's `Databases:` line) all see at least one read/write in the diff.
- Pagination defaults match (S1-F12: page=0, size=10, max size=100).
- Date range semantics: `[startDate T00:00:00, endDate T23:59:59.999]`; 400 if start > end.
- Idempotency on S3-F11: marker lives in Neo4j, NOT PG.
- Distinct-endpoint rule: M2 endpoints with collisions (S2-F10 `/full-text`, S3-F10 `/dashboard`, S5-F12 `/refund-surge-adjusted`) are NEW endpoints; M1 versions still exist.
- Strategy boundary (DP-1): no `RefundStrategy`/`Strategy`-named class outside payment-service; no `if (refundSurge)` inside payment-service.
- 15% surge fallback: readers (S5-F10) handle missing `surgeFee` as `0.15 * amount`.

If branch doesn't touch an M2 feature, N/A.

## Step 16: Six-DB K8s Sanity (only meaningful when `k8s/` is in the diff)

Per `docs/m3/k8s-manifests.md` and uber-m3.md:1748–1758:

- 5 per-service Postgres StatefulSets, each pinned to `postgres:17` (uber-m3.md:1627), `resources.limits.memory: 512Mi`, `pg_isready` probe.
- Mongo, Redis, ES, Neo4j, Cassandra StatefulSets present with the §10.8 memory caps + per-DB probes.
- RabbitMQ StatefulSet + Service exposing 5672 + 15672, `rabbitmq-diagnostics ping` probe.
- All 6 service Deployments use `resources.limits.memory: 768Mi` and have readiness/liveness probes on `/actuator/health`.
- API Gateway is `type: NodePort` on `nodePort: 30080`. All other services are `ClusterIP`.
- Headless services for the 5 PG StatefulSets.
- Critical Rule #6 (uber-m3.md:2640): no plain `Deployment` for a stateful database — must be `StatefulSet`.

If `k8s/` is not in the diff, N/A. **Compose** (M2 `docker-compose.yaml`) checks have moved to "compose-sanity" mode — only fire if `docker-compose.yaml` is in the diff (used for local dev only; K8s is the graded surface).

## Step 17: Per-Service Datasource Isolation (uber-m3.md:65–100, Critical Rule #1)

If the branch touches Java code or YAML in any of the 5 services:

- Datasource URL pattern: `jdbc:postgresql://<svc>-postgres:5432/uberdb-<svc>s`. Anything containing `postgres:5432/uberdb` (the M1/M2 shared URL) is a FAIL.
- Source-scan: no JDBC connection string in the diff references another service's DB:
  ```
  git diff main..HEAD -- '<service>/src/main/java/**/*.java' '<service>/src/main/resources/**' \
    | grep -E '^\+' \
    | grep -E "jdbc:postgresql://(user|driver|ride|location|payment)-postgres" \
    | grep -v "<this-service>-postgres"
  ```
  Must be empty (uber-m3.md:2635 — "**No cross-service JDBC.** Zero tolerance.").
- Source-scan: no native SQL `@Query` joining tables from another service.

If branch doesn't touch service code, N/A.

## Step 18: Feign Clients in `contracts/` (uber-m3.md:2570)

If the branch touches Feign code:

- `@FeignClient` interfaces live under `contracts/.../feign/`, NOT under `<service>/src/main/java/`. Source-scan:
  ```
  grep -rEn "@FeignClient" <service>/src/main/java/ --include='*.java'
  ```
  Must be empty (the service consumes Feign clients but does NOT declare them inline).
- The DTOs returned by Feign clients live under `contracts/.../dto/`.
- Every cross-service Feign call in the service is wrapped in try-catch (uber-m3.md:227–241).
- Per-element fan-out features (S1-F6, S1-F9, S3-F12, S4-F3, S4-F9, S5-F10) cap their candidate set at 100 elements (uber-m3.md:380).

If branch doesn't touch Feign, N/A.

## Step 19: RabbitMQ Topology (uber-m3.md:2637–2638)

If the branch declares an exchange, queue, publisher, or consumer:

- Exchange type is `TopicExchange` (uber-m3.md:286).
- Every consumer queue has `x-dead-letter-exchange` + `x-dead-letter-routing-key` arguments (Critical Rule #4, uber-m3.md:2638).
- `acknowledge-mode: auto`, `default-requeue-rejected: false`, `retry.max-attempts: 3` (Critical Rule #3, uber-m3.md:2637) — verify in `application.yml`.
- Routing keys match the §2.9 table (`docs/m3/event-actions.md`). Made-up routing keys are FAIL.
- Publish-after-commit: the publisher logic commits the local PG transaction first, then publishes (uber-m3.md:360–362). Source-scan for `@Transactional`-wrapped publishes is a FAIL.

If branch doesn't touch RabbitMQ, N/A.

## Step 20: Idempotent Consumers (Critical Rule #11, uber-m3.md:2645)

For every `@RabbitListener` method in the diff:

- The handler reads the target row's status before mutating (state-based idempotency).
- Examples: `ride.completed` consumer in driver-service skips if the rideId has already been counted; `payment.completed` consumer in ride-service checks `ride.status != PAID` before transitioning.
- A consumer that blindly mutates without a state guard is a FAIL.

If branch doesn't add a consumer, N/A.

## Step 21: Gateway JWT Filter (uber-m3.md:1469–1487, Critical Rule #8)

If the branch touches `api-gateway/`:

- `api-gateway` is the 7th Maven module in the root `pom.xml` (uber-m3.md:1405).
- pom.xml uses `spring-cloud-starter-gateway-server-webflux` + `spring-boot-starter-webflux`. **No `spring-boot-starter-web`** (uber-m3.md:1434) — that conflicts with webflux.
- The JWT filter implements `org.springframework.cloud.gateway.filter.GlobalFilter` (NOT `OncePerRequestFilter`). Signature: `Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)`.
- The filter is `@Component` AND implements `Ordered` returning `-1` (or `@Order(-1)`).
- `/api/auth/**` is bypassed.
- Successful validation forwards `X-User-Id`, `X-User-Role`, `X-Correlation-ID` headers to the downstream request (uber-m3.md:1481–1487).
- 5 route entries in `application.yml` (one per service, predicates match `Path=/api/<svc>/**` per uber-m3.md:1438–1465).
- Gateway Service is `type: NodePort, nodePort: 30080`.

If branch doesn't touch the gateway, N/A.

## Step 22: K8s + Observability Slice Artifacts (uber-m3.md:2542–2544)

For every M3 vertical slice (per uber-m3.md:2538 — "every deliverable touches Java + K8s + observability"), verify the slice's expected artifacts are present in the diff.

- **Slice A (READ-DB):** `<svc>-postgres` StatefulSet + PVC + Secret + headless Service; service `logback-spring.xml` with Loki4J appender; ≥3 LogQL panel definitions in the dashboard JSON.
- **Slice B (EVENTS):** Spring Boot Deployment + ClusterIP Service + ConfigMap; actuator config in `application.yml`; ≥3 PromQL panel definitions in the dashboard JSON; `<svc>EventConfig.java` (TopicExchange + queue + DLQ); publisher + consumer methods.
- **Slice C (INFRA):** the service's gateway route entry + Prometheus scrape job entry + final dashboard JSON (≥3 LogQL + ≥3 PromQL panels per uber-m3.md:1894); plus the assigned shared-infra item from §13.2.

`logback-spring.xml` checklist (uber-m3.md:1813–1851):
- `Loki4jAppender` configured to push to `http://loki.monitoring.svc.cluster.local:3100/loki/api/v1/push`.
- Label pattern includes `app=uber,service=${spring.application.name},level=%level,env=k8s`.
- Message pattern is the JSON log shape with the per-service MDC keys (uber-m3.md:1805–1812).
- A correlation-ID `OncePerRequestFilter` is registered in the service's security config (uber-m3.md:1855).

If branch doesn't include `k8s/`, observability artifacts, or service Deployments, N/A.

## Output Format

```
Pre-PR Check: <branch-name>
═══════════════════════════
Branch type: <type>

 1. Git conventions:                   [PASS/FAIL]
 2. Incremental commits:               [PASS/WARN/FAIL]
 3. Layered architecture:              [PASS/FAIL]
 4. Entity compliance:                 [PASS/FAIL]
 5. CRUD completeness:                 [PASS/FAIL]
 5b. CRUD conventions:                 [PASS/FAIL]
 6. Build verification:                [PASS/FAIL]
 7. Code style:                        [PASS/WARN]
 8. AI authorship hidden:              [PASS/FAIL]   ← critical
 9. M3 branch conventions:             [PASS/FAIL/N/A]
10. application.yml + M3 blocks:       [PASS/FAIL/N/A]
11. JWT on new endpoints:              [PASS/FAIL/N/A]
12. Caching hooks:                     [PASS/FAIL/N/A]
13. Observer chain:                    [PASS/FAIL/N/A]
14. Design pattern locations:          [PASS/FAIL/N/A]
15. M2 carry-over feature behavior:    [PASS/FAIL/N/A]
16. Six-DB K8s sanity:                 [PASS/FAIL/N/A]
17. Per-service datasource isolation:  [PASS/FAIL/N/A]   ← M3 critical
18. Feign clients in contracts/:       [PASS/FAIL/N/A]   ← M3
19. RabbitMQ topology + DLQ:           [PASS/FAIL/N/A]   ← M3
20. Idempotent consumers:              [PASS/FAIL/N/A]   ← M3 critical
21. Gateway reactive JWT filter:       [PASS/FAIL/N/A]   ← M3
22. K8s + observability slice:         [PASS/FAIL/N/A]   ← M3

Overall: READY / NOT READY
```

For each FAIL, list:
- Exact file + line.
- The spec section that mandates the rule.
- The fix.

## PR Description Generation (only on READY)

If the overall result is **READY**, generate a PR description in markdown. Analyze all commits on the branch (`git log main..HEAD`) and the actual code changes (`git diff main..HEAD`) to produce:

```markdown
## Summary
- <bullet point summarizing each logical change, not each commit>
- Focus on WHAT the feature does and WHY, not implementation details

## Changes
- **<Layer/File>**: <what was added/changed>
- ...

## Test scenario
1. <step from the feature spec's test scenario>
2. ...

## Cross-cutting impact (M2 only — omit section if all N/A)
- JWT: <new endpoints behind auth / N/A>
- Caching: <new keys / new invalidations / N/A>
- Observers: <new actions emitted / N/A>
- Design patterns: <which DPs touched / N/A>
```

Present the generated PR description in a code block so the user can copy it directly. **Never include AI-authorship markers.** Stay to the point and summarize accurately.
