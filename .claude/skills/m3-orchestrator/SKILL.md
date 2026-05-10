---
name: m3-orchestrator
description: End-to-end pipeline for building a single M3 vertical slice (S1-READ-DB..S5-INFRA per uber-m3.md §13.2). Verifies prerequisites (per-service PG, JWT at gateway, Observer, Feign clients, RabbitMQ topology, K8s manifests), reads the M3 spec verbatim via spec-clause-finder, plans commits, walks the user through implementation with checkpoints, runs cache-audit + saga-validator (when applicable) + pr-check, and produces a PR-ready branch. Default starting point for any M3 deliverable.
---

# M3 Slice Orchestrator

You are running the full pipeline to take an M3 deliverable from "I want to build S<n>-<slice>" to "branch is ready, here's the `git push` command." The pipeline strings together the M3 skills and agents with human checkpoints between every stage. You do NOT auto-implement, auto-commit, or auto-push.

## CRITICAL — Authorship & Repository Boundaries (Zero Exceptions)

These rules override every other instruction in this skill.

- **Never reveal AI authorship.** No `Co-Authored-By`, `Generated-by`, "Made with Claude", "via Claude Code", `🤖`, or any similar trailer/tag/comment in commits, code, PRs, branch names, or file headers.
- **Never reference yourself.** No "I implemented", "Claude added", etc.
- **Never commit, push, merge, or open a PR directly.** The user's GitHub account/profile must be the actor on every git operation. Your job ends at "the branch is ready and the diff looks good."
- **Never `git config user.*`, `git config commit.gpgsign`, or otherwise alter Git identity.**
- **Stage and draft, don't act.** Show the user the exact commands; let them execute. Only run a command if the user explicitly says "go ahead and run it."

## Sources of Truth (Read First)

| Concern | Doc |
|---|---|
| M3 spec verbatim | `docs/m3/uber-m3.md` (use `spec-clause-finder --milestone m3`) |
| Saga events / payloads / scenarios A/B/C | `docs/m3/saga-events.md` |
| Feign contracts | `docs/m3/feign-contracts.md` |
| K8s manifests | `docs/m3/k8s-manifests.md` |
| Observability | `docs/m3/observability.md` |
| JWT contract (gateway + service) | `docs/m3/jwt-contract.md` |
| Caching (carries from M2) | `docs/m3/cache-matrix.md` |
| Mongo action vocab + RabbitMQ routing keys | `docs/m3/event-actions.md` |
| Design patterns (carry from M2) | `docs/m3/design-patterns.md` |
| Per-service yaml block | `docs/m3/yaml-fragments/<svc>.application.yml` |
| M2 invariants still graded | `docs/m3/m2-carryover.md` |

## Pipeline Overview

```
Stage 0 — Identity + slice scope                         → AskUserQuestion checkpoint
Stage 1 — Read M3 spec verbatim                           → AskUserQuestion checkpoint
Stage 2 — Prerequisite verification                       → STOP if anything missing
Stage 3 — Plan implementation (Java + K8s + observability)→ AskUserQuestion checkpoint
Stage 4 — Implementation (per-commit)                     → checkpoint per commit
Stage 5a — Author test script on disk                     → AskUserQuestion checkpoint
Stage 5b — Spec-compliance audit of every assertion       → AskUserQuestion checkpoint
Stage 5c — Run script (Bash) + iterate until 0 FAIL       → fail → return to Stage 4
Stage 5d — cache-audit + saga-validator (if applicable)   → fail → return to Stage 4
Stage 5e — Commit test script as test(<scope>): ...       → no separate checkpoint
Stage 6 — pr-check + PR description                       → READY → user pushes
```

Each stage produces a result the user reviews before the next stage runs.

## Stage 0 — Identity + Scope

1. Confirm developer (verify name + student ID against `team.json` and the team table in CLAUDE.md). Echo back full name, ID, assigned service. STOP if not a real team member.

2. Resolve the slice ID. The 15 deliverables are at uber-m3.md:2546–2562:

   - **S<n>-READ-DB** — DB isolation + Feign clients + read endpoints + per-service PG K8s + Logback + ≥3 LogQL panels.
   - **S<n>-EVENTS** — RabbitMQ topology + publishers/consumers + saga participation + Spring Boot K8s + actuator + ≥3 PromQL panels.
   - **S<n>-INFRA** — gateway route + scrape job + final dashboard JSON + assigned shared-infra item.

   If the user gave free-text ("the saga trigger"), dispatch `spec-clause-finder --milestone m3` to find the matching slice.

3. Print a one-paragraph "what we're about to do" summary:
   ```
   Building <slice ID> — <slice description> in <service>.
   Pipeline: spec → prerequisites → plan → implement → test → cache-audit → saga (if applicable) → pr-check.
   Estimated commits: 5–8. Branch: feat/M3/<scope>/<slice>/<studentID>.
   ```

4. **Checkpoint:** AskUserQuestion — proceed / abort. If proceed, also ask whether the user wants to skip Stage 2 (saves time when the service is already wired).

## Stage 1 — Read Spec Verbatim

1. Dispatch `spec-clause-finder --milestone m3` for the §3–§7 block matching the slice's service, plus §8 if the slice touches the saga (S3-EVENTS, all `ride.completed`/`ride.cancelled` consumers per uber-m3.md:1354–1361).

2. Extract into a structured table:

   | Field | Captured value |
   |---|---|
   | Branch | `feat/M3/<scope>/<slice>/<studentID>` |
   | Endpoints exposed | full path + method + auth + DTO shape |
   | Feign clients consumed | which `<other>ServiceClient` calls + which routes |
   | RabbitMQ events published | exchange / routing key / payload record |
   | RabbitMQ events consumed | queue name / DLQ / state-guard rule |
   | Required databases | per-service PG + which NoSQL (carry from M2) |
   | Cache TTLs | from `docs/m3/cache-matrix.md` |
   | Observer events | from `docs/m3/event-actions.md` (Mongo + RabbitMQ columns) |
   | Design patterns touched | from `docs/m3/design-patterns.md` |
   | K8s artifacts | StatefulSet / Deployment / ConfigMap / Service / dashboard JSON |
   | Saga participation | uber-m3.md:1354–1361 — yes/no |
   | Idempotency rules | state-guard examples for any consumers |

3. Display the table. **Checkpoint:** AskUserQuestion — "did I read this correctly?" Allow corrections.

## Stage 2 — Prerequisite Verification (Read-Only)

For each prerequisite below, verify it exists. STOP if anything fails.

1. **DB isolation** — service's `application.yml` datasource is `<svc>-postgres:5432/uberdb-<svc>s`. If still `postgres:5432/uberdb`: STOP with "Run `db-isolation-bootstrap` for <service> first."

2. **NoSQL wiring** — dispatch `nosql-bootstrap` in verify mode. STOP on failure.

3. **JWT wiring** — read service's `security/` package + the `api-gateway/src/main/java/.../auth/JwtValidator.java`. If gateway filter missing: STOP with "Run `gateway-bootstrap` first." If service-side `JwtAuthenticationFilter` missing: STOP with "Service-side defense-in-depth filter is missing — see `docs/m3/jwt-contract.md`."

4. **Observer wiring** — dispatch `observer-bootstrap` in verify mode. STOP on failure.

5. **Feign clients** — if the slice consumes Feign, verify the matching `@FeignClient` interfaces exist in `contracts/`. If not: STOP with "Run `feign-bootstrap` for <service> first."

6. **RabbitMQ topology** — if the slice publishes or consumes events, verify `<svc>EventConfig.java` has the right TopicExchange + queues + DLQs. If not: STOP with "Run `rabbitmq-bootstrap` for <service> first."

7. **K8s manifests** — verify `k8s/{statefulsets,deployments,services,configmaps}/` have the entries for this service. If not: STOP with "Run `kubernetes-bootstrap` for <slice> first."

8. **Observability** — verify the service has `logback-spring.xml`, the actuator config, and the correlation-ID filter. If not: STOP with "Run `observability-bootstrap` first."

Display a one-line per-prerequisite report. **Checkpoint.**

## Stage 3 — Plan the Implementation

Plan the commits as a vertical slice. Per uber-m3.md:2538:

> **Rule:** Each deliverable is a vertical slice that touches **all parts** of M3 — Java code, Kubernetes manifests, and observability artifacts. No deliverable is purely Java, K8s, or YAML.

Typical commit shape per slice:

| Slice type | Commits |
|---|---|
| READ-DB | (1) datasource isolation, (2) Feign clients, (3) read endpoint repository, (4) read endpoint service, (5) read endpoint controller, (6) K8s `<svc>-postgres` StatefulSet + headless Service, (7) `logback-spring.xml` + LogQL panels |
| EVENTS | (1) `<svc>EventConfig` (TopicExchange + queues + DLQs), (2) publishers, (3) consumers (state-guarded), (4) M1/M2 feature refactor to Feign + publish, (5) K8s Deployment + Service + ConfigMap, (6) actuator + PromQL panels |
| INFRA | (1) gateway route entry, (2) scrape-job entry, (3) dashboard JSON, (4) assigned shared-infra item (e.g., RabbitMQ K8s for S5-INFRA) |

Each commit is a small, logical step. **Checkpoint.**

## Stage 4 — Implementation (Per-Commit Checkpoints)

For each commit:

1. Surface the commit's intent.
2. **Checkpoint** — "ready to plan this commit?"
3. Write code via `Edit` / `Write`.
4. Show exact `git add ... && git commit -m "..."` commands. Format: `<type>(<scope>): <subject> (<studentID>)`. Cite DP-<n> in the subject if the commit implements a numbered design pattern.
5. Wait for user to commit (do NOT run unless user says "go ahead").
6. Confirm file changes match the plan.

After all commits land: `mvn clean package -DskipTests` to confirm the build is clean.

## Stage 5a — Author the Test Script

Hard rule: tests are persisted as a runnable script before they're executed. Inline curl chains in chat are not acceptable.

Categories (auto-generated from the captured table at Stage 1):

- **Spec cases** — every step from the §3–§7 "Test scenario" subsection.
- **Boundary cases** — empty inputs, max-length, off-by-one date ranges, unicode.
- **Auth & ownership** — missing/malformed/garbage/tampered/expired tokens, wrong role, ownership violation, ADMIN bypass, /health public.
- **Cross-DB consistency** — for any feature that writes to >1 store: trigger, verify all stores reflect it; soft-dep cases.
- **Cache** — miss / hit-faster / TTL expiry / write-then-read invalidation / list-not-cached.
- **Idempotency** — for any RabbitMQ consumer, double-deliver and assert state didn't double; for S3-F11, repeat-call data-stable.
- **Saga** — A/B/C from `docs/m3/saga-events.md` if the slice touches saga.
- **Error paths** — every error code listed in the spec.

Write the script to `<service>/scripts/test-<slice-id>.sh` (e.g., `ride-service/scripts/test-S3-EVENTS.sh`). Conventions:

- `#!/usr/bin/env bash` shebang; `chmod +x`.
- One assertion per case; each calls `report "(<id>) <description>" 0|1 "<failure detail>"`.
- Exit code = number of FAIL assertions.
- Final line: `TOTALS: <PASS> PASS / <FAIL> FAIL`.
- Idempotent fixtures from `RUN_ID="$(date +%s)$$"`.
- Stack-config overrides via env vars (`GATEWAY_URL`, `RABBIT_HOST`, etc.).
- Cleanup at top: drop ES index, clear `<service>_events` Mongo collection, purge test queues if any.

Display the path, the case checklist (mapped to spec citations), and **checkpoint**.

## Stage 5b — Spec-Compliance Audit

Build an audit table with one row per assertion. Columns: Case ID / What it asserts / Spec citation / Verdict (`KEEP` / `WEAKEN` / `CONTRADICTS` / `MOVE`).

Use `spec-clause-finder` whenever in doubt. Cross-check against the M3 invariant docs:

- Status codes must match the spec exactly (don't assert codes the spec doesn't list).
- Cache keys + TTLs + invalidation patterns must match `docs/m3/cache-matrix.md`.
- Action vocabulary must be in `docs/m3/event-actions.md` (UPPER_SNAKE_CASE).
- Routing keys must be in the §2.9 table.
- Design pattern hard rules must reflect the actual rules.
- Endpoint coexistence: distinct-path features (S2-F10/full-text, S3-F10/dashboard, S5-F12/refund-surge-adjusted, plus M3-new saga pre-check endpoints) must not assert the M1 endpoint is gone.
- Gateway tests assert reactive `GlobalFilter` behavior (uber-m3.md:1469–1487).

Display the audit table. For each `WEAKEN` / `CONTRADICTS` / `MOVE`, propose the diff. Apply via `Edit` after user confirms. **Checkpoint.**

## Stage 5c — Run the Script via Bash, Iterate Until 0 FAIL

Run via `Bash`: `./<service>/scripts/test-<slice-id>.sh`. Surface `TOTALS:` and any `FAIL` rows verbatim.

If any case FAILs: diagnose. Code bug → return to Stage 4. Flaky assertion / wrong expected value → return to Stage 5b for re-audit.

Repeat until exit code 0.

## Stage 5d — cache-audit + saga-validator

- Always run `cache-audit` scoped to affected endpoints.
- If the slice touches the saga (S3-EVENTS or any slice that consumes `ride.*` / `payment.*` per uber-m3.md:1354–1361): run `saga-validator`.

Display PASS/FAIL per check. Failures → return to Stage 4.

## Stage 5e — Commit the Test Script

Surface:
```
git add <service>/scripts/test-<slice-id>.sh
git commit -m "test(<scope>): add <slice-id> end-to-end runner script (<studentID>)"
```

Wait for user (or run on explicit "go ahead").

## Stage 6 — PR Readiness

1. Dispatch `pr-check`. Display PASS/FAIL verbatim.

2. If **READY**:
   - Surface PR description (generated by `pr-check`) for user to copy.
   - Show:
     ```
     git push origin <branch-name>
     gh pr create --title "..." --body-file <file>   # or paste in GitHub UI
     ```
   - Remind: **regular merge commit** on GitHub (NOT squash). Do NOT delete the branch after merge.

3. If **NOT READY**: list each FAIL with file/line + spec section. Offer to return to Stage 4.

The orchestrator's job ends when the user has the push/PR commands. **Do NOT execute them.**

## Output Conventions

- One-line summary after each stage: "Stage N complete: <X result>. Moving to Stage N+1."
- If user aborts, leave working tree as-is.
- If user re-invokes on the same branch later, detect in-progress branch and ask whether to resume or restart.
