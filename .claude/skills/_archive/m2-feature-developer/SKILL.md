---
name: m2-feature-developer
description: Interactive workflow to start a new Milestone 2 feature (S1-F10..S5-F12). Handles JWT, multi-DB wiring, observer logging, caching, and design-pattern hooks that M2 features all touch. Use this instead of feature-developer for M2 work.
---

# Start a New M2 Feature

You are helping the user start a new M2 feature following the exact workflow required by the auto-grader. M2 features differ from M1 features in three structural ways: every endpoint requires JWT, every feature touches at least 2 databases, and several features must wire into specific design patterns.

The single source of truth for behaviour is `Uber_descriptionM2.pdf` at the repo root. When in doubt, prefer that PDF over CLAUDE.md or memory.

## Sources of Truth (Read First)

Before planning a feature, skim the relevant doc(s) for that feature's concerns:

| If the feature involves... | Consult... |
|---|---|
| Caching / TTL / invalidation | `docs/m2/cache-matrix.md` |
| Observer events (any feature with a `Mongo` line in its DB list) | `docs/m2/event-actions.md` |
| Builder / Strategy / Adapter / CoR wiring | `docs/m2/design-patterns.md` |
| Configuration (yml additions) | `docs/m2/yaml-fragments/<service>.application.yml` |

The PDF (§10.x for the feature spec) is always the final authority on behavior, error codes, and test scenarios — use `pdf-clause-finder` for verbatim clauses. The docs above are kept in sync with the PDF and are your fastest reference. If a doc disagrees with this skill, trust the doc.

## CRITICAL — Authorship & Repository Boundaries (Zero Exceptions)

These rules override every other instruction in this skill. If a step below would violate one, STOP and ask the user how to proceed.

- **Never reveal AI authorship.** Do not add `Co-Authored-By`, `Generated-by`, "Made with Claude", "via Claude Code", `🤖`, or any similar trailer/tag/comment in commits, code, PRs, branch names, file headers, or any other artifact. Treat this as part of the auto-grader's hard-fail set.
- **Never reference yourself.** Do not say "I implemented", "Claude added", "the assistant created" in commit messages, PR titles, PR descriptions, code comments, or docs. Write everything as the human developer would write it — first person if natural, but never with AI attribution.
- **Never commit, push, merge, or open a PR directly.** Even if you have shell access. The user's GitHub account/profile must be the actor on every git operation visible to GitHub or the auto-grader. Your job ends at "the branch is ready and the diff looks good" — the user runs `git commit`, `git push`, and `gh pr create` (or the GitHub UI equivalent) themselves.
- **Never `git config user.*`, `git config commit.gpgsign`, or otherwise alter who Git thinks is committing.** The user's existing local git identity is what the auto-grader checks against `team.json`.
- **Stage and draft, don't act.** When the user is ready to commit, you may show them the exact `git add ...` / `git commit -m "..."` command(s) to run. Same for push and PR creation — show the commands, do not execute them. If the user explicitly says "go ahead and commit", *only then* run the command, and still as the user (no AI trailer in the message).

If at any step you find yourself about to push, merge, open a PR, or attribute the work to AI: stop and route it back to the user.

## Step 1: Gather Information

Ask the user (use AskUserQuestion):
- Which feature? (S1-F10, S1-F11, S1-F12, S2-F10..S2-F12, S3-F10..S3-F12, S4-F10..S4-F12, S5-F10, S5-F11, S5-F12)
- Their student ID

Determine the service from the feature ID:
- S1 = user-service (`user`)
- S2 = driver-service (`driver`)
- S3 = ride-service (`ride`)
- S4 = location-service (`location`)
- S5 = payment-service (`payment`)

## Step 2: Verify Developer Identity

Cross-check the student ID against the team table in CLAUDE.md. The student must be assigned to the feature's service. STOP and warn if either check fails. Echo back the developer's name, ID, assigned service, and feature service. Identity must be verified before any branch is created.

## Step 3: M1 Retrofit Gate (M2-specific, do NOT skip)

Most M2 features depend on M1 retrofits (BCrypt, JWT filter, Redis caching, Observer chain, surgeFee, description key, driver auto-index). Before planning a new feature, run a quick gate:

1. Confirm `application.yml` exists in the target service (not `application.properties`).
2. Confirm `JwtAuthenticationFilter` (or equivalent) exists in the service's security package.
3. Confirm `MongoEventLogger` and `EntityObserver` exist in the service.
4. Confirm `JwtConfigurationManager` (Singleton, not a Spring bean) exists somewhere accessible to the service.
5. For S2 (Driver): confirm Elasticsearch client wiring + auto-index hook on Driver CRUD.
6. For S3 (Ride): confirm Neo4j wiring.
7. For S4 (Location): confirm Cassandra wiring.
8. For S5 (Payment): confirm `transactionDetails.surgeFee` is being written by S5-F4.

If any retrofit is missing, STOP and tell the user: "M1 retrofit `<name>` is missing — run the `m1-retrofit-runner` skill first. M2 feature work cannot land cleanly without it."

## Step 4: Verify Clean State

Run `git status`. If dirty, ask the user to stash or commit before proceeding. Never proceed with a dirty tree.

## Step 5: Create Branch

```
git checkout main
git pull origin main
git checkout -b feat/<service-shortname>/S{n}-F{m}/<studentId>
```

Example: `git checkout -b feat/payment/S5-F12/55-25376`

## Step 6: Load Feature Spec from the PDF

Use the `pdf-clause-finder` agent (or `Read` with the right `pages` range) to extract the exact feature block from `Uber_descriptionM2.pdf` (§10.x). Do not work from memory.

From the spec, extract and record:

| Field | Captured value |
|---|---|
| Branch | `feat/<svc>/S{n}-F{m}/<id>` |
| Endpoint | exact path + method + query params |
| Auth | Required (USER/ADMIN) or public |
| Databases | the explicit list (e.g., "Cassandra, PostgreSQL, MongoDB") |
| Request body | shape + required fields |
| Response | shape + status code on success |
| Error codes | every numbered error case the spec lists |
| Ownership check | only if spec says "ownership" — note exact rule (uid==path OR ADMIN) |
| Cache TTL | if spec says "cache N minutes" |
| Observer events | every event the spec says to emit (action vocabulary, UPPER_SNAKE_CASE) |
| Design patterns | any DP the spec references (Strategy, Builder, etc.) |
| Idempotency | if spec mentions it (e.g., S3-F11) |

If any of these are unclear in the spec, STOP and ask the user — do not infer.

## Step 7: Dependency Check

Search the codebase for what already exists:

1. **Own service:** read `model/`, `repository/`, `service/`, `controller/`, plus `event/` (observer), `config/` (security), `dto/`. Note what's there.
2. **NoSQL clients:** confirm the templates the feature needs are wired:
   - Mongo: `MongoTemplate` or a Spring Data Mongo repository on the relevant collection.
   - Redis: `RedisTemplate` / `@Cacheable`-style infrastructure.
   - Elasticsearch (S2 only): `ElasticsearchOperations` / `ElasticsearchClient`.
   - Neo4j (S3 only): `Neo4jClient` / Spring Data Neo4j repository.
   - Cassandra (S4 only): `CqlSession` / Spring Data Cassandra repository.
3. **Cross-service PG access:** if the feature joins tables across services (e.g., S5-F10 joins payments/rides/drivers), confirm the FK columns exist via plain `Long` fields on M1 entities. Do **not** introduce inter-service HTTP — that's M3.
4. **Adapter requirement:** if the feature reads from a NoSQL source and returns a DTO, the read path must go through an Adapter (`MongoDocumentAdapter`, `Neo4jRecordAdapter`, etc.). If the matching adapter doesn't exist, plan it as a commit.
5. **Builder requirement:** if the feature returns a DTO with 5+ fields, plan a Builder commit (or external `<DtoName>Builder` for records).

Report findings in the same format as the existing `feature-developer` skill before continuing.

## Step 8: Cache & Invalidation Plan (M2-specific)

For every M2 feature, decide the cache plan **before** writing code:

- **Read endpoints**: cache key follows `<service>::S{n}-F{m}::<param-hash>`. TTL per the spec (5 min search/activity, 10 min dashboards/analytics, 5 min recommendations, 5 min tracking).
- **Write endpoints**: list the keys this feature must invalidate. Use wildcard deletion (`<service>::S{n}-F{m}::*`) when the feature output may include the changed entity.
- **Observer-driven invalidation**: if the feature is a write (or its Observer event is data-mutating), it auto-invalidates dashboard caches. `ANALYTICS_VIEWED` and `DASHBOARD_VIEWED` are **excluded** from invalidation — they're observability-only. Match on the action string before invalidating.
- **Logging-on-cache-hit**: dashboard features (S2-F12, S3-F10, S4-F10, S5-F10) must emit their `*_VIEWED` event on **every** invocation, including cache hits. The logging step must run **outside** the cache decorator/layer. Plan this explicitly.

## Step 9: Design-Pattern Wiring Plan

Map the feature to required patterns:

| Feature | Patterns it touches |
|---|---|
| S1-F10 (register) | Observer (REGISTERED), Factory, Singleton (JWT) |
| S1-F11 (login) | Observer (LOGGED_IN), Factory, Singleton |
| S1-F12 (activity feed) | CoR (auth), Adapter (Mongo→DTO), Builder if DTO has 5+ fields |
| S2-F10 (full-text search) | CoR (auth), Adapter (ES hit→DTO), Builder if 5+ fields |
| S2-F11 (index driver) | Observer (INDEXED / DRIVER_DELETED), Factory |
| S2-F12 (driver dashboard) | Observer (DASHBOARD_VIEWED), Factory, **Builder (DriverDashboardDTO)** |
| S3-F10 (ride analytics dashboard) | Observer (ANALYTICS_VIEWED), Factory, **Builder (RideAnalyticsDashboardDTO)** |
| S3-F11 (record interaction) | Observer (INTERACTION_RECORDED only on non-idempotent path) |
| S3-F12 (recommendations) | CoR (auth + ownership), Adapter (Neo4j Record→DTO) |
| S4-F10 (location analytics) | Observer (ANALYTICS_VIEWED), Factory, **Builder (LocationAnalyticsDTO)** |
| S4-F11 (record GPS) | Observer (TRACKING_RECORDED), Factory |
| S4-F12 (tracking timeline) | Adapter (Cassandra Row→DTO) |
| S5-F10 (vehicle-type revenue) | Observer (ANALYTICS_VIEWED), Factory, **Builder (VehicleTypeRevenueDTO)** |
| S5-F11 (payment methods breakdown) | Observer (ANALYTICS_VIEWED), Adapter (Mongo→DTO) |
| S5-F12 (refund-surge-adjusted) | **Strategy (full/base/no-refund)** + Selector, Observer (REFUNDED / REFUND_DENIED), Factory |

Mention the DP in commit messages where it applies (e.g., "implements DP-1 Strategy").

## Step 10: Detailed Implementation Plan (Commits)

Break the feature into commits. M2 features should typically span 5–8 commits because they touch multiple databases:

1. **DTO + Builder** (if 5+ fields)
2. **Adapter** (if reading from a NoSQL source)
3. **NoSQL repository / template wiring** (if not already present)
4. **PG repository changes** (any new `@Query` method)
5. **Service layer** — business logic, validation, ownership check (if needed), strategy selector (S5-F12), observer notifications, cache key/TTL
6. **Controller layer** — endpoint, `@Valid` on `@RequestBody`, response status codes
7. **Cache invalidation** for any write paths affected
8. **Refinements** — edge cases, 400/401/403/404 codes, empty-list returns

Commit-message DP citation: "feat(payment-service): add FullRefundWithSurgeStrategy (implements DP-1 Strategy) (55-25376)".

Present the plan in the same format used by `feature-developer`, then wait for the user to approve.

## Step 11: Confirm and Implement

Implement the commits one by one. After each commit, re-confirm the build still compiles.

## Step 12: Test the Feature

Test must cover:

1. **Happy path** from the spec's "Test scenario:" subsection — every step, exact status codes.
2. **Auth**: call without `Authorization` header → 401; with malformed token → 401; with expired token → 401.
3. **Role/ownership**: where the spec mentions ownership (S1-F12, S3-F12), call with another user's token → 403; call with ADMIN → 200.
4. **Cache**: call twice with same params + same token. Inspect Redis (`redis-cli -a redispass KEYS '<service>::*'`). Second call must be faster.
5. **Observer**: after the call, check the matching MongoDB collection has the right action document.
6. **Cache invalidation** (writes only): trigger the write, confirm the cached key is gone.
7. **Edge cases listed in the spec**: 400 invalid input, 404 not found, empty-result returns.

Report PASS/FAIL per step in the same table format used by `feature-developer`. If anything fails, fix as a separate commit.

## Step 13: Stop & Hand Off

When all tests pass:
- Stop any local processes you started.
- Tell the user: "Branch ready — push and open a PR. Use the regular merge commit on GitHub. Do NOT delete the branch after merging."
- Do **not** push or open the PR yourself unless the user explicitly asks.
