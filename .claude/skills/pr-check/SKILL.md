---
name: pr-check
description: Full pre-PR verification checklist. Run this before creating a pull request to catch all auto-grader-failing issues at once. Covers M1 conventions (git, layered architecture, CRUD, build) AND M2 conventions (JWT, caching, observers, design patterns, application.yml, AI-authorship trailers).
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

- **`docs/m2/cache-matrix.md`** — Section 12 (caching hooks).
- **`docs/m2/event-actions.md`** — Section 13 (observer chain + action vocabulary).
- **`docs/m2/design-patterns.md`** — Section 14 (DP locations + grader hooks).
- **`docs/m2/yaml-fragments/<service>.application.yml`** — Section 10 (application.yml correctness).
- **`Uber_descriptionM2.pdf`** — final authority. Use `pdf-clause-finder` for verbatim clauses.

If a doc disagrees with this skill, trust the doc and flag the drift to the user.

## Step 0: Determine Branch Scope

Read the current branch name. Classify it (used for reporting, NOT for skipping checks):

- `feat/<service>/S{n}-F[1-9]/<id>` → M1 feature branch.
- `feat/<service>/S{n}-F1{0,1,2}/<id>` → M2 feature branch.
- `feat/m1/MOD-<n>/<id>` → M1 retrofit branch.
- `feat/cc/CC-<n>[-...]/<id>` → cross-cutting branch.
- `feat/docker/<id>` → docker work.
- `fix/...`, `hotfix/...`, `refactor/...`, etc. → branch type set in §2 of the M2 PDF.

Print the resolved branch type at the top of the report.

## Step 1: Git Conventions

Check the current branch name and all commits on this branch (vs main):

- Branch matches `<type>/<scope>/<descriptor>/<studentId>` pattern (per M2 §2).
- `<type>` ∈ {feat, fix, hotfix, refactor, docs, test, chore, perf}.
- `<scope>` is a service shortname (`user`, `driver`, `ride`, `location`, `payment`) for per-service work, or one of `m1`, `cc`, `infra` for cross-cutting work.
- For M1 features (F1–F9): branch is `feat/<service>/S{n}-F<m>/<id>`.
- For M2 features (F10–F12): branch is `feat/<service>/S{n}-F1{0,1,2}/<id>`.
- For M1 retrofits: branch is `feat/m1/MOD-<n>/<id>` with the stable MOD-n descriptor.
- All commit messages match `<type>(<service-name>): <description> (<studentId>)` format.
- Student ID is consistent across branch name and all commits.
- Student ID matches a member in `team.json`.
- The service in the branch matches the member's assigned service.
- Note: branch uses the service shortname (`driver`); commit message uses the full service name (`driver-service`).
- **Branch must NOT be `main`** — every change must come via PR (M2 §2 explicitly penalizes pushing direct to main).

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

**Cross-Service Rules**
- Forbidden: `@ManyToOne`, `@OneToMany`, `@ManyToMany` referencing entities from other services, importing entity classes from other service packages.
- Required: Plain `Long` fields for foreign keys, native SQL `@Query` for cross-table JOINs.

**Relationship Rules**
- Required: `@JsonIgnore` on the inverse side (the `List<>`/`Set<>` side) of all bidirectional relationships.

For each FAIL, report exactly which file and line violates which rule and suggest the fix.

## Step 4: Entity Compliance

For each entity in the service, verify it matches the spec:
- All fields present with correct types.
- Correct JPA annotations (`@Entity`, `@Id`, `@GeneratedValue`, relationships).
- No extra fields beyond the spec (warn, don't fail).
- For M2-additive JSONB keys: `Driver.vehicleDetails.description` (default empty string), `Payment.transactionDetails.surgeFee` (compute from surgeMultiplier or 15% fallback).

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

This is a hard rule from CLAUDE.md. Run:

```
git log main..HEAD --format=%B | grep -iE "co-authored-by|generated.with|claude code|anthropic|openai|chatgpt|copilot"
```

The output MUST be empty. Also `git diff main..HEAD` for similar markers in source files. **FAIL** if any match — instruct the user to amend/rebase the commits to drop the trailer (or, if already pushed, force-push with the user's permission).

## Step 9: M2 Branch Conventions Sanity

If this is an M2-relevant branch (any non-M1-feature branch — i.e., M2 feature, M1 retrofit, cross-cutting, infra):
- Cross-cutting branches use scope `cc` (e.g., `feat/cc/CC-5/<id>`), not the service name.
- M1 retrofit branches use `feat/m1/MOD-<n>/<id>` exactly.
- For DP-spanning commits, subject cites `DP-<n>`.

If pure M1 hotfix (e.g., `fix/driver/null-handling/<id>`), this section is N/A.

## Step 10: application.yml Present (CC-6)

For each service touched by this branch, confirm `src/main/resources/application.yml` exists and there is **no** `application.properties` left over. The grader explicitly checks for YAML.

```
find <service>/src/main/resources -name 'application.properties' -o -name 'application.yml'
```

If neither exists, FAIL. If `application.properties` exists, FAIL (must migrate per CC-6). For M1-only branches that don't touch config, this section is N/A.

## Step 11: JWT Filter on New Endpoints (CC-1)

If the branch touches a controller, every new endpoint (other than `/api/auth/register`, `/api/auth/login`, health) must require auth.

- The service has `JwtAuthenticationFilter` registered in `SecurityConfig`.
- The new controller does NOT use `@PermitAll` or `permitAll()` for its routes.
- Spot-test: call the new endpoint without an `Authorization` header → 401.

For CC-2 (`PUT /api/users/{id}/role`): without ADMIN role → 403; with ADMIN → 200.

If branch doesn't touch any controller, N/A.

## Step 12: Caching Hooks (CC-3)

If the branch adds a read endpoint:
- It uses the cache key convention `<service>::S{n}-F{m}::<param-hash>` (or for entity detail `<service>::<entity>::<id>`).
- TTL matches `docs/m2/cache-matrix.md` (5 min search/activity/recommendations/tracking, 10 min dashboards/analytics, 15 min entity detail).
- For dashboards (S2-F12, S3-F10, S4-F10, S5-F10): the `*_VIEWED` event is logged on **every** invocation (including cache hits) — logging step runs **outside** the cache layer.

If the branch adds a write endpoint:
- It invalidates `<service>::<entity>::{id}` for the affected entity.
- It wildcard-deletes any `<service>::S{n}-F{m}::*` keys whose output may include the changed entity.
- Per `docs/m2/cache-matrix.md`, NoSQL-writer paths (S4-F11, S3-F11, S2-F11/CRUD auto-index, S5-F10/F11 audit-trail writes) must **also** invalidate the matching read caches even though no PG row mutates.

If unsure, run the `cache-audit` skill on the affected endpoints. Otherwise, spot-test: call the write, verify the cached key is gone via `redis-cli`.

If branch doesn't touch caching, N/A.

## Step 13: Observer Chain (CC-4 / DP-2 / DP-6)

If the branch adds a write endpoint:

- The service emits the matching event via `notifyObservers(action, payload)`. Spot-test by tailing the matching MongoDB collection during a request.
- The action string is in the `docs/m2/event-actions.md` vocabulary (UPPER_SNAKE_CASE).
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

Patterns must live in their assigned spots. Check based on what the branch touches (full reference: `docs/m2/design-patterns.md`):

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

## Step 15: M2 Feature Behavior (only meaningful on `feat/<service>/S{n}-F1{0,1,2}` branches)

Compare implemented behavior against the §10.x spec for the feature. Spot-check:

- HTTP status codes match the spec **exactly** (e.g., S1-F11 returns 401 for both wrong-password AND user-not-found — NOT 404).
- Ownership checks for S1-F12 and S3-F12 use direct numeric equality on `uid` claim vs path/query — no PG lookup.
- Required databases (per the feature's `Databases:` line) all see at least one read/write in the diff.
- Pagination defaults match (S1-F12: page=0, size=10, max size=100).
- Date range semantics: `[startDate T00:00:00, endDate T23:59:59.999]`; 400 if start > end.
- Idempotency on S3-F11: marker lives in Neo4j, NOT PG.
- Distinct-endpoint rule: M2 endpoints with collisions (S2-F10 `/full-text`, S3-F10 `/dashboard`, S5-F12 `/refund-surge-adjusted`) are NEW endpoints; M1 versions still exist.

If branch is not an M2 feature branch, N/A.

## Step 16: Six-DB Compose Sanity (only meaningful when `docker-compose.yaml` is in the diff)

- Postgres pinned to `postgres:17` (PG18 breaks Hibernate 7.2).
- Image tags exactly: `mongo:latest`, `redis:latest`, `elasticsearch:8.19.12`, `neo4j:latest`, `cassandra:latest`.
- Memory caps present: Redis `--maxmemory 256mb --maxmemory-policy allkeys-lru`; ES `ES_JAVA_OPTS=-Xms512m -Xmx512m`; Cassandra `MAX_HEAP_SIZE: 512M`; Neo4j `NEO4J_server_memory_heap_max__size: 512m`.
- Healthchecks present on all 6 DB services.
- Named volumes declared: `mongo-data`, `es-data`, `neo4j-data`, `cassandra-data`.
- `docker compose config` parses without error.
- Optional: `docker compose up -d` and confirm all 6 reach healthy within 120s, total `docker stats` < 5 GB.

If `docker-compose.yaml` is not in the diff, N/A.

## Output Format

```
Pre-PR Check: <branch-name>
═══════════════════════════
Branch type: <type>

 1. Git conventions:           [PASS/FAIL]
 2. Incremental commits:       [PASS/WARN/FAIL]
 3. Layered architecture:      [PASS/FAIL]
 4. Entity compliance:         [PASS/FAIL]
 5. CRUD completeness:         [PASS/FAIL]
 5b. CRUD conventions:         [PASS/FAIL]
 6. Build verification:        [PASS/FAIL]
 7. Code style:                [PASS/WARN]
 8. AI authorship hidden:      [PASS/FAIL]   ← critical
 9. M2 branch conventions:     [PASS/FAIL/N/A]
10. application.yml:           [PASS/FAIL/N/A]
11. JWT on new endpoints:      [PASS/FAIL/N/A]
12. Caching hooks:             [PASS/FAIL/N/A]
13. Observer chain:            [PASS/FAIL/N/A]
14. Design pattern locations:  [PASS/FAIL/N/A]
15. M2 feature behavior:       [PASS/FAIL/N/A]
16. Compose sanity:            [PASS/FAIL/N/A]

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
