# Uber Replica — Project Conventions

## Project Overview

Auto-graded GUC Spring 2026 project (Architecture of Massively Scalable Applications). Maven multi-module Spring Boot backend, **5 services + api-gateway + contracts module**, deployed to MiniKube Kubernetes. **No frontend.**

- **M1** (15%) = 45 features (9 per service) — DONE.
- **M2** (15%) = JWT auth, 5 NoSQL stores, 7 design patterns, Redis caching — DONE; invariants still graded.
- **M3** (40%) = database isolation, OpenFeign reads, RabbitMQ async events, Spring Cloud Gateway (reactive), MiniKube K8s, Loki4J + Prometheus + Grafana, choreography saga. **IN PROGRESS, due 17/05/2026.**
- **Team** = 15 members, 3 per service.

## Source of Truth

The single source of truth for M3 is `docs/m3/uber-m3.md`. **Defer to that file over CLAUDE.md / memory / training data when they disagree.** When you don't remember something exactly, use the `spec-clause-finder` agent rather than guessing.

```
M3 spec   → docs/m3/uber-m3.md           (use spec-clause-finder --milestone m3)
M2 spec   → Uber_descriptionM2.pdf       (use spec-clause-finder --milestone m2)

Carries over from M2:
  cache rules     → docs/m3/cache-matrix.md
  event vocab     → docs/m3/event-actions.md
  design patterns → docs/m3/design-patterns.md
  M2 behaviors    → docs/m3/m2-carryover.md

M3-only:
  saga events       → docs/m3/saga-events.md
  feign contracts   → docs/m3/feign-contracts.md
  K8s manifests     → docs/m3/k8s-manifests.md
  observability     → docs/m3/observability.md
  jwt contract      → docs/m3/jwt-contract.md
  per-svc YAML      → docs/m3/yaml-fragments/<service>.application.yml
  stack pins        → docs/m3/stack.md
  M1/M2 baseline    → docs/m3/baseline.md
```

## Session Setup — Identify the Developer

At the start of every conversation where code will be written, committed, or branches created, you MUST confirm who is currently developing. **Do not assume based on memory or prior conversations.**

Ask: "Which team member is working? (name or student ID)"

Verify against the team table below and confirm full name + student ID + assigned service back to the user. Use this student ID for **all** branch names and commit messages in the session. If the user tries to commit or branch without confirming identity first, stop and ask.

| Service          | Members |
|------------------|---------|
| ride-service     | Mohamed Khaled (55-25378), Ahmed Wael (55-13512), Youssef Malek (55-24816) |
| payment-service  | Seif Tarek Mostafa (55-24853), Yahia Hesham (55-25376), Seifeldin Hesham (55-0664) |
| location-service | Omar Elharridy (55-0654), Ahmed El-Mosallamy (55-0823), Youssef Maged (55-2829) |
| user-service     | Ahmed Gamal (55-24478), Abdelrahman Mohamed (55-26445), Seif Tarek Ahmed (55-3258) |
| driver-service   | Mahmoud Hebishy (55-18387), Ahmed Gasser (55-25085), Ziad Raafat (55-7978) |

## CRITICAL — AI Authorship Must Be Hidden

**NEVER** add `Co-Authored-By`, `Generated-by`, or any other trailer/tag/comment that reveals AI involvement in commits, code, PRs, or any artifact. No one should know that Claude wrote any code. **Hard rule, zero exceptions.**

## Branch & Commit Conventions (M3 §13.1)

The auto-grader cross-references `team.json` against git history. Violations = **ZERO credit**.

Branch format (the only one accepted by `.githooks/post-checkout` from M3 onward):

```
<type>/M3/<ID>/<studentID>
<type>/M3/<scope>/<ID>/<studentID>
```

- `<type>` ∈ {feat, fix, bugfix, hotfix, release, docs, refactor, test, chore}.
- `M3` is literal — the milestone marker. The earlier M1/M2 format `<type>/<service>/<feature>/<id>` is **rejected**.
- `<scope>` (optional): `user`, `driver`, `ride`, `location`, `payment` for per-service work; `cc`, `infra`, `githooks`, etc. for cross-cutting.
- `<ID>`: `S<n>-<ID>` (e.g., `S5-F4`, `S5-INFRA`, `S3-F11`), or a short kebab-case slug for cross-cutting work.
- `<studentID>`: always last; required by the auto-grader.

Examples: `feat/M3/S5-INFRA/55-24853`, `feat/M3/payment/S5-F4/55-24853`, `chore/M3/githooks/55-24853`.

Commit subject: `<type>(<scope>): <imperative subject> (<studentID>)` — keep ≤72 chars, no trailing period. When implementing a numbered design pattern, **cite the DP ID in the subject**.

> **Historical M1/M2 branches still exist on `origin` and in `main`'s merge history.** Checking one out locally will trip the hook and auto-delete the local copy. To inspect a historical branch: `git -c core.hooksPath= checkout <branch>` (bypasses the hook for that one command).

**Per-member zero rule:** any team member with no commits, or whose commits cannot be matched to any feature branch, gets a ZERO. Do not push directly to `main` — every change must come through a PR.

**Merge rules:** regular merge commits only ("Create a merge commit" on GitHub). NEVER squash. NEVER delete feature branches after merging — the auto-grader verifies branch existence.

## Development Workflow

**NEVER one-shot a feature.** 3–5 incremental commits minimum: repository → service → controller → refinements. Each commit is a small, logical step that simulates a human developer working step by step.

**Tests live on disk.** Persisted at `<service>/scripts/test-<feature-id>.sh`, committed on the feature branch as a `test(<scope>): ...` commit before the PR is opened. Inline curl chains in chat are not acceptable. The orchestrator handles this — see "When in doubt" below.

## Architecture (full reference: `docs/m3/baseline.md`)

Strict three-layer pattern: **Controller → Service → Repository → DB**.

- Controller: HTTP handling only. Validate, call service, return response. No business logic.
- Service: all business logic. No `@RequestMapping`, no JDBC, no `EntityManager`.
- Repository: `JpaRepository` interfaces only. `@Query` for complex queries within this service's own DB.

**Cross-service rules (M3):** no `@ManyToOne` to other services' entities (plain `Long` instead per uber-m3.md:104). No native `@Query` joining across services. **No cross-service JDBC.** Reads → Feign clients in `contracts/` (uber-m3.md:2570). Writes → RabbitMQ events on `<svc>.events` TopicExchange.

## Health Endpoints (public)

`GET /api/users/health`, `GET /api/drivers/health`, `GET /api/rides/health`, `GET /api/locations/health`, `GET /api/payments/health` — all return `OK`. The api-gateway bypasses these alongside `/api/auth/**`.

## When in Doubt

- Building any M3 slice → use the **`m3-orchestrator`** skill (default starting point).
- Need verbatim spec text → use the **`spec-clause-finder`** agent.
- Before opening a PR → use the **`pr-check`** skill.
- Wiring a specific subsystem → `db-isolation-bootstrap` / `feign-bootstrap` / `rabbitmq-bootstrap` / `gateway-bootstrap` / `kubernetes-bootstrap` / `observability-bootstrap` / `nosql-bootstrap` / `observer-bootstrap`.
- Caching uncertainty → `cache-audit`. Saga end-to-end check → `saga-validator`.
- Endpoint-by-endpoint compliance snapshot → **`endpoint-cataloger`** agent.
- Retro-coverage tests on a merged feature → **`feature-tester`** agent.
- Entity definitions → **`verify-entity`** skill.
