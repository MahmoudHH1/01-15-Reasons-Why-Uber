# Skills & Agents — Quick Reference

Short index of what's in `.claude/`. Skills are workflows you invoke (`/<name>` or "use the X skill"); agents are subagents Claude dispatches via the `Agent` tool or you call with `@<name>`.

## Skills (in `.claude/skills/`)

| Skill | When to use | Why |
|---|---|---|
| **m2-orchestrator** | Default starting point for any S1-F10..S5-F12 feature | End-to-end pipeline with human checkpoints: verifies prerequisites, reads spec verbatim, plans commits, walks implementation, runs tests + cache-audit + pr-check, leaves the branch ready for the user to push |
| **m2-feature-developer** | Building an M2 feature directly (or invoked by m2-orchestrator) | Multi-DB / JWT / observer / cache / DP wiring that M1 `m1-feature-developer` doesn't cover |
| **m1-retrofit-runner** | Once, before any M2 feature work begins | Walks the team through Section 4 retrofits (BCrypt, JWT filter, Redis caching, Observer chain, surgeFee, description key, simulateFailure, ES auto-index) in dependency order |
| **nosql-bootstrap** | Wiring or verifying a service's NoSQL clients | Two modes — `bootstrap` creates pom deps + yml + entity skeletons; `verify` audits existing wiring against `Uber_descriptionM2.pdf` §6/§7 |
| **jwt-bootstrap** | Wiring JWT into a service for the first time | Creates `JwtConfigurationManager` (Singleton), `JwtService`, `JwtAuthenticationFilter` with the AuthHandler chain (CoR), and per-service `SecurityConfig` |
| **observer-bootstrap** | Wiring the GoF Observer chain into a service | Creates `EntityObserver`, `MongoEventLogger` bound to a fixed EventType, subject mixin, EventFactory dispatch, and one wired demo write. Enforces the no-`@EventListener`-to-Mongo rule. |
| **cache-audit** | After M1 retrofits land; before any caching-touching PR | Verifies all 37+ cached endpoints, TTLs, key formats, and 48+ invalidation paths against §4.4. Probes Redis live. |
| **pr-check** | Before opening any PR (M1 or M2) | Single merged checklist — git conventions, layered architecture, CRUD, build, AI-authorship scan, plus M2 checks for JWT, caching, observers, design patterns, application.yml, compose sanity |

## Agents (in `.claude/agents/`)

Invoke either by typing `@<name>` in your prompt, or by asking Claude to dispatch via the `Agent` tool.

| Agent | When to use | Why |
|---|---|---|
| **pdf-clause-finder** | Anytime you need the literal text of a §X.Y clause, table, or test scenario from `Uber_descriptionM2.pdf` | Returns verbatim quote + section + page. Avoids paraphrasing drift on grader-checked details (action vocabularies, image tags, error codes, payload shapes). |
| **endpoint-cataloger** | Before/after a retrofit pass, or before a release-readiness review | Walks all 5 services, produces a single table of every endpoint with its M2 compliance state — JWT applied, cache key + TTL, observer events emitted, design patterns wired, M1-vs-M2 path coexistence. Heavier, run sparingly. |
| **feature-tester** | After implementing a feature; or anytime you want retro-coverage on an existing one | Runs the spec test scenario PLUS auto-generated boundary / auth / cross-DB / cache / idempotency / error cases against the live stack. Returns structured PASS/FAIL with concrete fix hints. Cleans up its own test data. |

## Companion Docs (in `docs/m2/`)

These are the data the skills/agents reference. Keep them in sync with code as M2 progresses:

- [cache-matrix.md](../docs/m2/cache-matrix.md) — every cached + invalidated endpoint
- [event-actions.md](../docs/m2/event-actions.md) — UPPER_SNAKE_CASE action vocabularies per service
- [design-patterns.md](../docs/m2/design-patterns.md) — all 7 patterns with locations + grader hooks
- [yaml-fragments/](../docs/m2/yaml-fragments/) — per-service `application.yml` reference

## Recommended Order for the Team

1. Read [CLAUDE.md](CLAUDE.md) → "Milestone 2 Conventions" section.
2. Run `m1-retrofit-runner` → land MOD-1..MOD-9 + CC-5/CC-6 across services.
3. Use `nosql-bootstrap` (bootstrap mode) per service that's missing wiring; verify mode for the rest.
4. Use `jwt-bootstrap` and `observer-bootstrap` per service.
5. Run `cache-audit` once retrofits are done — sanity-check coverage.
6. Use `m2-orchestrator` for each F10–F12 branch — it walks the entire pipeline (spec → prereqs → plan → implement → test → cache-audit → pr-check) with checkpoints.
7. The orchestrator runs `pr-check` automatically at the end; you never invoke it directly unless you skip the orchestrator and build a feature manually.

## Deprecated for M2 (kept only for M1 reference)

These skills were built during M1 and are superseded for M2 work. They still live in `.claude/skills/` because the auto-grader still tests M1 features, but **do not invoke them for M2 tasks** — they don't know about JWT, NoSQL wiring, caching, observers, or design patterns.

| Skill | Replacement for M2 | Why deprecated |
|---|---|---|
| **m1-feature-developer** | `m2-feature-developer` (or `m2-orchestrator` for the full pipeline) | Assumes a single PG database; doesn't enumerate JWT, multi-DB wiring, cache TTLs, observer events, or design-pattern hooks that M2 features all touch. |
| **verify-entity** | (no replacement needed) | M2 doesn't add or change any M1 PostgreSQL entity schema — only additive JSONB keys (`surgeFee`, `description`) that the spec confirms. NoSQL document/node/row classes are verified by `nosql-bootstrap` (verify mode), not this skill. |
