# Uber Replica — Project Conventions

## Project Overview

This is an auto-graded university project (Architecture of Massively Scalable Applications, GUC Spring 2026). We are building a ride-hailing platform (Uber replica) as a **Maven multi-module Spring Boot** backend with 5 services sharing a single PostgreSQL database.

- **Milestone 1** = 15% of final grade, 45 features (9 per service) — DONE
- **Milestone 2** = 15% of final grade, +15 features (F10–F12 per service), JWT auth, 5 NoSQL stores, 7 design patterns, Redis caching — IN PROGRESS
- **Team** = 15 members, 3 per service
- **Stack:** Java 25 / JDK 25 (Docker base `eclipse-temurin:25.0.2_10-jdk`), **Spring Boot 4.0.4** (user-service still on 4.0.3 — bump in a separate `chore` commit), Spring Data JPA, PostgreSQL 17 (NOT 18 — breaks Hibernate 7.2), Docker, Maven
- **No frontend** — backend architecture only

## Milestone 2 — Source of Truth

The single source of truth for M2 is `Uber_descriptionM2.pdf` at the repo root. **Defer to that PDF over CLAUDE.md / memory / training data when they disagree.** When you don't remember something exactly, use the `pdf-clause-finder` agent rather than guessing.

Companion docs (kept in sync with the PDF):
- [docs/m2/cache-matrix.md](../docs/m2/cache-matrix.md) — every cached/invalidated endpoint
- [docs/m2/event-actions.md](../docs/m2/event-actions.md) — UPPER_SNAKE_CASE action vocabularies per service
- [docs/m2/design-patterns.md](../docs/m2/design-patterns.md) — all 7 patterns with locations + grader hooks
- [docs/m2/yaml-fragments/](../docs/m2/yaml-fragments/) — per-service `application.yml` reference

## Milestone 2 Conventions

### Stack additions

- Spring Boot **4.0.3** on JDK 25.
- **Jackson dual dependency**: 3.x (`tools.jackson.*`) for Spring Boot runtime, 2.x (`com.fasterxml.jackson.*`) ONLY for Hibernate 7.2's JSONB FormatMapper. Do not remove the 2.x deps.
- **PostgreSQL pinned to `postgres:17`**. PG18 breaks Hibernate 7.2 — explicitly called out in the spec.
- **`application.yml` everywhere** (CC-6). Auto-grader requires YAML — no `application.properties` may remain.

### Six-database topology (CC-5)

| DB | Image (exact) | Internal port | Memory cap |
|---|---|---|---|
| postgres | `postgres:17` | 5432 | default |
| mongo | `mongo:latest` | 27017 | default |
| redis | `redis:latest` | 6379 | `--maxmemory 256mb --maxmemory-policy allkeys-lru` |
| elasticsearch | `elasticsearch:8.19.12` | 9200 | `ES_JAVA_OPTS=-Xms512m -Xmx512m` |
| neo4j | `neo4j:latest` | 7687 / 7474 | `NEO4J_server_memory_heap_max__size: 512m` |
| cassandra | `cassandra:latest` | 9042 | `MAX_HEAP_SIZE: 512M` |

Total stack target: **<5 GB**. Auto-grader reads `docker stats` and image tags.

**Soft vs hard dependencies (§6.3):**
- PostgreSQL = hard. Service won't boot without it.
- Mongo / Redis / ES / Neo4j / Cassandra = **soft**. Service must still boot when these are down. NoSQL failures `log.warn` and **must not** roll back the upstream PG transaction. `MongoEventLogger` in particular catches and swallows Mongo exceptions.

### JWT contract (CC-1, §5)

- Algorithm: HMAC-SHA256.
- Secret: ≥32 bytes (256 bits) when Base64-decoded. Short readable strings throw `WeakKeyException`.
- **Same secret across all 5 services** — user-service issues tokens, every other service verifies independently (§5.1). Hardcoded in each service's `application.yml` (must be the same value everywhere).
- Payload: `sub` = email, `uid` = `User.id` (Long, custom claim — used by ownership checks), `role` = role string, `iat`, `exp`.
- Header: `Authorization: Bearer <token>`.
- Expiration: 24 hours (`86400000` ms).
- Public-only endpoints: `POST /api/auth/register`, `POST /api/auth/login` (user-service only), and the 5 health checks. Anything else public = grading failure.
- Per-service security config: stateless sessions, CSRF disabled.
- Token staleness after role change is an accepted limitation — no revocation list. Demoted ADMINs retain ADMIN for up to 24h.

### Cache key & invalidation conventions (CC-3, §4.4)

- Entity detail: `<service>::<entity>::<id>` (e.g., `user-service::user::42`).
- Feature result: `<service>::S<n>-F<m>::<param-hash>` (e.g., `user-service::S1-F3::42`).
- Invalidation = **wildcard deletion** (`SCAN + DEL` or `KEYS + UNLINK`). Over-invalidation is acceptable.
- **List endpoints (`GET /api/<entity>`) are NOT cached.** Only GET-by-ID for the 10 entities + 27 M1 feature GETs + M2 feature reads.
- TTLs: dashboards/analytics 10 min, search/recommendations/activity feeds 5 min, entity detail 15 min, F1=5m, F3=10m, F5=5m, F6=10m, F8=15m, F9=10m.
- `ANALYTICS_VIEWED` and `DASHBOARD_VIEWED` actions **do NOT invalidate caches** — they're observability-only, and triggering invalidation on them creates a self-defeating cycle. Match on the action string before invalidating.
- Full enumeration in [docs/m2/cache-matrix.md](../docs/m2/cache-matrix.md). Run the `cache-audit` skill before any PR that touches caching.

### Design pattern locations (CC-4)

| # | Pattern | Where it lives | Hard rule |
|---|---|---|---|
| DP-1 | Strategy | `payment-service` S5-F12 ONLY | grader greps for `Strategy`/`RefundStrategy` outside payment-service. Service must NOT contain `if (refundSurge)`. |
| DP-2 | Observer | all 5 services | classical GoF (`EntityObserver` interface, `MongoEventLogger` concrete, `register/unregister/notifyObservers` on subjects). NO `@EventListener` may write to MongoDB. |
| DP-3 | Chain of Responsibility | JWT auth | `AuthHandler` chain built INSIDE `JwtAuthenticationFilter.doFilterInternal()`. Do NOT replace Spring's filter chain. |
| DP-4 | Builder | dashboard/analytics DTOs + retrofitted M1 DTOs with 5+ fields | static `builder()` returning fluent setters returning `this`, terminal `build()`. Records use external `<DtoName>Builder`. S2-F8 / S3-F8 skip Builder (they return entities). |
| DP-5 | Singleton | `JwtConfigurationManager` | private constructor only, public static `getInstance()`, **NOT** annotated with any Spring stereotype. `JwtService` is the Spring bridge that calls `getInstance()`. |
| DP-6 | Factory | Mongo event creation | `EventFactory.createEvent(EventType, Map<String, Object>)`. NO `new AuthEvent(...)` / `new DriverEvent(...)` / etc. anywhere outside the factory — graded by source-scan. |
| DP-7 | Adapter | NoSQL → DTO | one adapter per source per service: `MongoDocumentAdapter` (×5), `ElasticsearchHitAdapter` (driver), `Neo4jRecordAdapter` (ride), `CassandraRowAdapter` (location). For S1-F3, `ObjectArrayDtoAdapter`. No universal Entity-Dto base. |

Full reference + grader hooks in [docs/m2/design-patterns.md](../docs/m2/design-patterns.md).

### Observer chain rules

- Mongo writes go through `MongoEventLogger` only. Never `@EventListener` to Mongo. Never `new <Event>(...)` — go through `EventFactory`.
- Each service binds its `MongoEventLogger` to a **fixed** `EventType` at construction (user→AUTH, driver→DRIVER, ride→RIDE, location→LOCATION, payment→PAYMENT_AUDIT). Observer registration is per-service, not shared.
- The action string (UPPER_SNAKE_CASE) passed to `notifyObservers(actionString, payload)` is **NOT** the EventType passed to the factory. The action string travels in `params["action"]`.
- Action vocabularies in [docs/m2/event-actions.md](../docs/m2/event-actions.md). Stick to those values; extend only with domain-appropriate UPPER_SNAKE_CASE names.
- Payment-shaped actions (CREATED/COMPLETED/FAILED/REFUNDED/REFUND_DENIED/RETRY_ATTEMPTED) **must** carry `method` and `amount` on the event. Otherwise S5-F11 silently drops the event from the breakdown.

### Strategy boundary

The Strategy pattern is used **only** in S5-F12 (refund logic). Do not introduce a `RefundStrategy`/`Strategy`-named class anywhere else — graded by grep. Don't force Strategy onto any M1 method.

### Ownership-check pattern

Used by S1-F12 (`GET /api/users/{id}/activity`) and S3-F12 (`GET /api/rides/recommendations`):

- Compare the JWT `uid` claim (numeric) directly against the path/query userId. **No PG lookup** on the hot path.
- Caller passes if `uid == target` OR `role == "ADMIN"`. Otherwise 403 (not 404 — exposing 404 leaks existence).

### Dashboard logging-on-cache-hit

Dashboard features (S2-F12, S3-F10, S4-F10, S5-F10) emit their `*_VIEWED` event on **every** invocation, **including cache hits**. The logging step must run **outside** the cache decorator/layer so cache hits log too. These observability writes are excluded from observer-driven cache invalidation (see above).

### Distinct-endpoint rule for M2-vs-M1 collisions

Several M2 features have new paths that coexist with M1 endpoints:

- S2-F10 `/api/drivers/search/full-text` ≠ M1 `/api/drivers/search`
- S3-F10 `/api/rides/analytics/dashboard` ≠ M1 `/api/rides/analytics`
- S5-F12 `/api/payments/{id}/refund-surge-adjusted` ≠ M1 `/api/payments/{id}/refund`

Both must coexist. Do not overwrite the M1 endpoint when adding the M2 one.

### Idempotency rule for S3-F11

`POST /api/rides/{rideId}/record-interaction` is idempotent on `rideId`. The idempotency marker lives **in Neo4j**, not PostgreSQL — M2 does not alter any M1 PG schema (per §1). Acceptable approaches: a `recorded_ride_ids` set on the `RODE_WITH` relationship, or a sentinel `(:User)-[:RECORDED_RIDE {rideId}]->(:Driver)` node checked with EXISTS. Idempotent re-calls return 200 immediately and do **not** emit `INTERACTION_RECORDED`.

### CRUD writes auto-index drivers to ES

The Driver entity must auto-sync to Elasticsearch on every CRUD POST/PUT (re-index) and DELETE (remove). Implement via JPA entity listener (`@PostPersist/@PostUpdate/@PostRemove`) or a service-level hook. **Do not** inline the ES call in every controller method. The explicit `POST /api/drivers/{id}/index` endpoint (S2-F11) and the auto-index path emit `INDEXED` events with `details.source ∈ {"explicit", "auto_crud_create", "auto_crud_update"}` (and `DRIVER_DELETED` on remove).

### 15% surge fallback

Pre-M2 Payment rows lack `transactionDetails.surgeFee`. Any reader (S5-F10 in particular) treats a missing/null `surgeFee` as `0.15 * amount` (15% of total). New writes (M1 S5-F4 retrofit) compute the fee from `Ride.metadata.surgeMultiplier` if present (`baseFare * (multiplier - 1)`), else 15% of total. No DB backfill migration.

### Branch & commit conventions (M2 expansions, §2)

Branch format: `<type>/<scope>/<descriptor>/<studentID>`.

- `<type>`: `feat`, `fix`, `hotfix`, `refactor`, `docs`, `test`, `chore`, `perf` (M2 expanded the M1 set).
- `<scope>`: `user`, `driver`, `ride`, `location`, `payment` (per-service), or `m1`, `cc`, `infra` (cross-cutting).
- `<descriptor>`: stable ID (`S<n>-F<m>`, `MOD-<n>`, `CC-<n>`, `DP-<n>`) when one exists; otherwise a short kebab-case slug.
- `<studentID>`: always last. Required by the auto-grader.

Examples: `feat/user/S1-F10/55-24478`, `feat/m1/MOD-3/55-25085`, `feat/cc/CC-5/55-8080`, `fix/payment/refund-amount-rounding/55-8080`, `hotfix/user/token-expiry-leak/55-8080`.

Commit subject: `<type>(<scope>): <imperative subject> (<studentID>)` — keep ≤72 chars, no trailing period. When a commit implements a numbered design pattern, **cite the DP ID in the subject** (e.g., "implements DP-1 Strategy").

Cross-cutting work uses scope `cc` (e.g., `feat(cc): CC-5 add elasticsearch + neo4j + cassandra to compose (55-25085)`).

**Per-member zero rule:** any team member with no commits, or whose commits cannot be matched to any feature branch, gets a ZERO. Per-member checks affect only that member; team-wide checks (CRUD missing, build broken) affect everyone. Do not push directly to `main` — every change must come through a PR.

### When in doubt

- Unsure about a clause → use the `pdf-clause-finder` agent.
- **Starting any S1-F10..S5-F12 feature → use the `m2-orchestrator` skill** (default starting point; runs the full pipeline with checkpoints).
- Building a feature manually without the orchestrator → use the `m2-feature-developer` skill.
- Before opening a PR → use the `pr-check` skill (single merged checklist, M1 + M2). The orchestrator already runs it at the end of its pipeline.
- Wiring a new NoSQL store → use the `nosql-bootstrap` skill (it has a verify mode for already-wired services).
- Wiring JWT or Observers → use `jwt-bootstrap` / `observer-bootstrap`.
- Caching uncertainty → use the `cache-audit` skill.
- Testing a feature end-to-end (live stack, edge cases included) → use the `feature-tester` agent.
- Catalogue endpoints + their compliance state → use the `endpoint-cataloger` agent.

## Session Setup — Identify the Developer

At the start of every conversation where code will be written, committed, or branches created, you MUST confirm who is currently developing. **Do not assume based on memory or prior conversations.**

Ask: "Which team member is working? (name or student ID)"

Then verify against the team table below and confirm back:
- Full name
- Student ID
- Assigned service

Use this student ID for **all** branch names and commit messages in the session. If the user tries to commit or branch without confirming identity first, stop and ask.

## CRITICAL — AI Authorship Must Be Hidden

**NEVER** add `Co-Authored-By`, `Generated-by`, or any other trailer/tag/comment that reveals AI involvement in commits, code, PRs, or any artifact. No one should know that Claude wrote any code. This is a **hard rule with zero exceptions**.

## CRITICAL — Auto-Grader Rules

The auto-grader cross-references `team.json` against git history. Violations = **ZERO credit**.

### Branch Naming (Mandatory)

```
feat/<service>/<feature-name>/<studentId>
```

Examples:
- `feat/user/S1-F1/55-24478`
- `feat/driver/S2-F3/55-25085`
- `feat/docker/55-25085` (for dockerization)

### Commit Message Format (Mandatory)

```
feat(<service-name>): <description> (<studentId>)
```

Examples:
- `feat(driver-service): add Driver entity model (55-25085)`
- `fix(driver-service): fix null handling in search query (55-25085)`

The auto-grader matches the Git author (from `team.json`) against the student ID in the commit message. Mismatched or missing IDs = **zero credit for the member AND team deductions**.

### Merge Rules

- Use **regular merge commits** only ("Create a merge commit" on GitHub)
- **NEVER** use squash merge — the auto-grader needs the branch name preserved in merge commit messages
- **NEVER** delete feature branches after merging — the auto-grader verifies branch existence

## Development Workflow — Incremental Commits

**NEVER one-shot a feature.** Every feature must be built through multiple incremental commits that simulate a human developer working step by step:

1. **Repository layer** — add query methods, custom @Query
2. **Service layer** — add business logic that uses the repository
3. **Controller layer** — add the endpoint that calls the service
4. **Refinements** — edge cases, fixes, cleanup

Each commit should be a small, logical step. A feature branch should have 3-5 commits minimum, not one giant commit.

### Feature Branch Workflow

```
git checkout main && git pull origin main
git checkout -b feat/<service>/<feature-ID>/<studentId>
# ... implement incrementally with multiple commits ...
git push origin feat/<service>/<feature-ID>/<studentId>
# Create PR on GitHub, get 1+ teammate review, merge with regular merge commit
```

## Testing Workflow — Tests Live On Disk

**Tests for a feature must be persisted as a runnable shell script** at `<service>/scripts/test-<feature-id>.sh`, committed on the feature branch as a `test(<service>): ...` commit, **before** the PR is opened. Inline ad-hoc curl chains pasted into chat are not acceptable — they evaporate the second the conversation ends and cannot be re-run by graders, teammates, or future-you.

Every feature test script must:

1. **Cover all 7 case categories** (per `m2-orchestrator` Stage 5a): spec scenarios, boundary, auth & ownership, cross-DB consistency, cache, idempotency, error paths. A category with no applicable cases for the feature must be explicitly noted (`# (no idempotency cases — endpoint is read-only)`), not silently omitted.
2. **Tie every assertion to a spec citation.** Each case must map to a §10.x clause / `docs/m2/cache-matrix.md` row / `docs/m2/event-actions.md` row / `docs/m2/design-patterns.md` rule / JWT-contract clause. Tests that assert implementation defaults beyond what the spec mandates are over-constraints — drop them. The orchestrator's `Stage 5b` is a dedicated **spec-compliance audit**: every assertion gets a verdict of `KEEP` / `WEAKEN` / `CONTRADICTS` / `MOVE` against the relevant doc, with `pdf-clause-finder` dispatched on any uncertainty. The audit runs **after writing the script and before any execution** so over-constraints are caught while the script is cheap to edit, not after a fix loop has cemented them in. Common over-constraint shapes to catch: asserting status codes the spec doesn't list, asserting cache patterns missing from the cache matrix, asserting Mongo `action` strings outside the event-actions vocabulary, asserting design-pattern artifacts in services the spec doesn't assign them to, and asserting the absence of M1 endpoints during an M2-feature distinct-path test.
3. **Be idempotent across runs.** Derive unique fixtures from `RUN_ID="$(date +%s)$$"` so re-running the script doesn't crash on unique constraints. Drop the ES index / clear the `<service>_events` Mongo collection at the top.
4. **Be configurable.** Every stack-config value (service URLs, Mongo creds, Redis password) must be overridable via env var with a default that matches `docker-compose.yaml`.
5. **Exit zero only when every case passes.** Exit code = number of FAIL assertions. Final line: `TOTALS: <PASS> PASS / <FAIL> FAIL`. CI integration: `./script && echo green`.

The orchestrator's `Stage 5a` writes the script, `Stage 5b` audits every assertion against the spec (KEEP / WEAKEN / CONTRADICTS / MOVE) before any execution, `Stage 5c` runs it via `Bash` (NOT via the `feature-tester` agent — that agent is read-only and cannot author or amend the script during the fix loop), `Stage 5e` commits it. The `feature-tester` agent is reserved for retro-coverage runs on already-merged features.

## Code Style — Human-Like Code

- **Comments:** Use sparingly. Only when the logic is genuinely non-obvious. Keep them short and natural.
- **No** excessive javadoc on every method
- **No** auto-generated boilerplate comments (e.g., "This method does X")
- **No** commenting obvious code — let the code speak for itself
- Write clean, readable code that doesn't need comments to understand
- Variable/method names should be self-documenting

## Architecture — Layered Pattern (STRICT)

Every service follows this strict layered architecture. The auto-grader tests that each feature respects proper layering.

```
Client Request
      ↓
┌─────────────┐
│ Controller  │  → HTTP handling ONLY. No business logic.
│             │    Validate request, call service, return response.
└──────┬──────┘
       ↓
┌─────────────┐
│  Service    │  → ALL business logic lives here.
│             │    Business rules, validation, orchestration.
└──────┬──────┘
       ↓
┌─────────────┐
│ Repository  │  → Database operations ONLY.
│             │    JpaRepository interfaces, @Query methods.
└──────┬──────┘
       ↓
   Database
```

### Package Structure (per service)

```
src/main/java/com/team01/uber/<service>/
├── controller/    # REST controllers
├── service/       # Business logic
├── repository/    # JpaRepository interfaces (PG) + NoSQL repositories where applicable
├── model/         # JPA entity classes + NoSQL document/node/row classes
├── dto/           # Data transfer objects (with Builder where 5+ fields)
├── event/         # M2: EntityObserver, MongoEventLogger, EventFactory, MongoEvent classes
├── adapter/       # M2: NoSQL → DTO adapters (one per NoSQL source)
├── security/      # M2: JwtConfigurationManager (Singleton), JwtService, JwtAuthenticationFilter, AuthHandler chain
└── config/        # SecurityConfig, cache config, observer registration
```

The `event/`, `adapter/`, `security/`, and `config/` packages are M2 additions. For payment-service, also add a `strategy/` package for the Strategy DP (S5-F12).

### Dependency Injection Flow

Controller depends on Service. Service depends on Repository. Use constructor injection or `@Autowired`.

## Entity & Database Rules

- All entities use **auto-generated Long IDs** (`@GeneratedValue(strategy = GenerationType.IDENTITY)`)
- **JSONB columns:** Use `Map<String, Object>` with Hibernate JSONB annotations (from Lab 4)
- **Enums:** Store as SQL ENUMs — use `@Enumerated(EnumType.STRING)` in JPA
- **Cross-service references:** Plain `Long` FK columns, **NOT** JPA-managed `@ManyToOne` relationships
- **Cross-service data access:** Native SQL `@Query` with JOINs only — never JPA relationships across services
- **JPA relationships** only between entities within the **same** service
- Use `@JsonIgnore` on the inverse side of bidirectional relationships to prevent infinite recursion

## Port & Database Configuration

| Service          | Internal Port | Docker Host Port |
|------------------|---------------|------------------|
| user-service     | 8080          | 8081             |
| driver-service   | 8080          | 8082             |
| ride-service     | 8080          | 8083             |
| location-service | 8080          | 8084             |
| payment-service  | 8080          | 8085             |

- Shared DB: `jdbc:postgresql://localhost:5432/uberdb` (or `postgres:5432` inside Docker)
- Credentials: `postgres` / `postgres`
- DDL: `spring.jpa.hibernate.ddl-auto=update`

## Repository Layer Rules

- One `JpaRepository<Entity, Long>` interface per entity
- **Naming-convention methods** for simple lookups (e.g., `findByEmail`)
- **Custom `@Query` with native SQL** for complex queries, including cross-service JOINs
- `@Modifying` + `@Transactional` for UPDATE/DELETE queries
- **All** database interaction goes through the repository layer — NEVER write queries in the service layer

## CRUD Baseline

CRUD operations (create, read by ID, read all, update, delete) are the **baseline for every entity** and do NOT count as features. However:

- The auto-grader **tests CRUD** and will not run feature tests without it
- Implement **all CRUD for all entities** before starting any features
- Each service needs CRUD for all its entities (e.g., Payment Service needs CRUD for Payment, Coupon, AND PaymentCoupon)

## Dockerization (Phase D)

- `Dockerfile` per service using `eclipse-temurin:25.0.2_10-jdk`
- Copy the service JAR, expose port 8080
- `docker-compose.yaml` maps host ports 8081-8085 to container port 8080
- Override datasource URL: `jdbc:postgresql://postgres:5432/uberdb`
- `depends_on` the PostgreSQL service
- Branch: `feat/docker/<studentId>`
- Build: `mvn clean package -DskipTests` then `docker compose up --build`

## Human-in-the-Loop Rules

- **NEVER** add features not explicitly stated in the project description (M1 PDF for M1 work, M2 PDF for M2 work)
- If an additional feature or helper seems needed to complete a described feature, **ALWAYS ask the human first** and get explicit approval
- **NEVER** make assumptions about requirements — consult the relevant description PDF
- When in doubt about any convention, ask before proceeding
- The description document is the single source of truth for what to implement. For M2 specifically, defer to `Uber_descriptionM2.pdf` over CLAUDE.md when they conflict.

## Services & Team Assignment

| Service          | Members |
|------------------|---------|
| ride-service     | Mohamed Khaled (55-25378), Ahmed Wael (55-13512), Youssef Malek (55-24816) |
| payment-service  | Seif Tarek Mostafa (55-24853), Yahia Hesham (55-25376), Seifeldin Hesham (55-0664) |
| location-service | Omar Elharridy (55-0654), Ahmed El-Mosallamy (55-0823), Youssef Maged (55-2829) |
| user-service     | Ahmed Gamal (55-24478), Abdelrahman Mohamed (55-26445), Seif Tarek Ahmed (55-3258) |
| driver-service   | Mahmoud Hebishy (55-18387), Ahmed Gasser (55-25085), Ziad Raafat (55-7978) |

## Health Endpoints

Each service exposes a health check:
- `GET /api/users/health` → "OK"
- `GET /api/drivers/health` → "OK"
- `GET /api/rides/health` → "OK"
- `GET /api/locations/health` → "OK"
- `GET /api/payments/health` → "OK"
