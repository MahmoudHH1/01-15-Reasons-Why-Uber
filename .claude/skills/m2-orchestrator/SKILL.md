---
name: m2-orchestrator
description: End-to-end pipeline for building a single M2 feature. Verifies prerequisites (NoSQL, JWT, Observer wiring), reads the spec verbatim, plans commits, walks the user through implementation with checkpoints, runs cache-audit + pr-check, and produces a PR-ready branch — without committing or pushing on the user's behalf. Use this as the default starting point for any S1-F10..S5-F12 feature.
---

# M2 Feature Orchestrator

You are running the full pipeline to take an M2 feature from "I want to build S<n>-F<m>" to "branch is ready, here's the `git push` command." The pipeline strings together existing skills and agents with human checkpoints between every stage. You do NOT auto-implement, auto-commit, or auto-push.

## CRITICAL — Authorship & Repository Boundaries (Zero Exceptions)

These rules override every other instruction in this skill. They are inherited from `m2-feature-developer`.

- **Never reveal AI authorship.** No `Co-Authored-By`, `Generated-by`, "Made with Claude", "via Claude Code", `🤖`, or similar trailer/tag/comment in commits, code, PRs, branch names, or file headers.
- **Never reference yourself.** No "I implemented", "Claude added", etc. in commit messages, PR titles, PR descriptions, code comments, or docs.
- **Never commit, push, merge, or open a PR directly.** The user's GitHub account/profile must be the actor on every git operation. Your job ends at "the branch is ready and the diff looks good."
- **Never `git config user.*`, `git config commit.gpgsign`, or otherwise alter Git identity.**
- **Stage and draft, don't act.** Show the user the exact `git add ...` / `git commit -m "..."` / `git push` commands; let them execute. Only run a command if the user explicitly says "go ahead and run it" — and even then, never with an AI trailer.

If at any stage you find yourself about to push, merge, open a PR, or attribute work to AI: stop and route it back to the user.

## Sources of Truth (Read First)

This skill orchestrates other skills/agents that already reference the M2 docs. Re-list here so the user knows what's in scope:

| Concern | Doc |
|---|---|
| Caching / TTL / invalidation | `docs/m2/cache-matrix.md` |
| Observer events / action vocabulary | `docs/m2/event-actions.md` |
| Design pattern locations + grader hooks | `docs/m2/design-patterns.md` |
| application.yml shape per service | `docs/m2/yaml-fragments/<service>.application.yml` |
| Final authority for behavior | `Uber_descriptionM2.pdf` (use `pdf-clause-finder` for verbatim) |

## Pipeline Overview

```
Stage 0 — Identity + scope                    → AskUserQuestion checkpoint
Stage 1 — Read spec verbatim                  → AskUserQuestion checkpoint
Stage 2 — Prerequisite verification           → STOP if anything missing
Stage 3 — Plan implementation                 → AskUserQuestion checkpoint
Stage 4 — Implementation (per-commit)         → checkpoint per commit
Stage 5a — Test case design                   → AskUserQuestion checkpoint
Stage 5b — Test execution (feature-tester)    → fail → return to Stage 4
Stage 5c — Cache audit                        → fail → return to Stage 4
Stage 6 — pr-check + PR description           → READY → user pushes
```

Each stage produces a result the user reviews before the next stage runs.

---

## Stage 0 — Identity + Scope

1. Confirm developer (verify name + student ID against `team.json` and the team table in CLAUDE.md). Echo back full name, ID, assigned service. STOP if not a real team member.

2. Resolve the feature ID:
   - If the user gave a feature ID (`S5-F12`, etc.), proceed.
   - If the user gave a free-text description ("the refund-with-surge thing"), dispatch `pdf-clause-finder` with a brief search query to find the matching §10.x block. Confirm with the user: "did you mean S5-F12 — Process Ride Refund with Surge Handling?"

3. Print a one-paragraph "what we're about to do" summary:
   ```
   Building <feature ID> — <feature title> in <service-name>.
   Pipeline: spec → prerequisites → plan → implement → test → cache-audit → pr-check.
   Estimated commits: 5–8 (typical for M2 features).
   ```

4. **Checkpoint:** AskUserQuestion — proceed / abort. If proceed, also ask whether the user wants to skip Stage 2 (prerequisite verification) — useful when they know the service is already wired and want to save the time. Default: don't skip.

## Stage 1 — Read Spec Verbatim

1. Dispatch `pdf-clause-finder` for the §10.x block of the resolved feature ID.

2. Extract into a structured table:

   | Field | Captured value |
   |---|---|
   | Branch | `feat/<svc>/S{n}-F{m}/<id>` |
   | Endpoint | full path + method + query params |
   | Auth | required (USER/ADMIN) or public |
   | Databases | exact list from spec |
   | Request body | shape + required fields |
   | Response | shape + success status code |
   | Error codes | every numbered error case |
   | Ownership check | only if spec mentions it |
   | Cache TTL | only if spec specifies |
   | Observer events | every action the spec says to emit |
   | Design patterns | every DP the spec references |
   | Idempotency | only if spec mentions it |

3. Display the table.

4. **Checkpoint:** AskUserQuestion — "did I read this correctly? Anything to correct before we plan?" Allow the user to edit any cell. The corrected table travels to Stage 3.

## Stage 2 — Prerequisite Verification (Read-Only)

For each prerequisite below, verify it exists in the target service. If anything fails, STOP with a clear message ("Run X skill first, then re-invoke me").

1. **NoSQL wiring** — dispatch `nosql-bootstrap` in **verify mode** for the service. Confirms pom deps, application.yml sections, document/entity classes, repositories, action vocabulary alignment, soft-dep handling.

2. **JWT wiring** — read the service's `security/` package. Confirm:
   - `JwtConfigurationManager.java` exists.
   - `JwtAuthenticationFilter.java` exists.
   - `JwtService.java` exists.
   - `SecurityConfig.java` exists.
   - If any missing: STOP with message "Run `jwt-bootstrap` for <service>, then re-invoke me."

3. **Observer wiring** — read the service's `event/` package. Confirm:
   - `EntityObserver` interface exists.
   - `MongoEventLogger` class exists.
   - `EventFactory` exists.
   - If any missing: STOP with message "Run `observer-bootstrap` for <service>, then re-invoke me."

4. **Service-specific prerequisites:**
   - For driver-service (S2): confirm Driver CRUD auto-index to ES is wired (JPA entity listener with `@PostPersist/@PostUpdate/@PostRemove`, OR a service-level hook). If missing: STOP.
   - For payment-service (S5): confirm `transactionDetails.surgeFee` is being written by S5-F4. If missing: STOP.

5. Display a one-line per-prerequisite report:
   ```
   Prerequisites for <service>:
     ✓ NoSQL wiring (Mongo, Redis<, ES/Neo4j/Cassandra>)
     ✓ JWT wiring
     ✓ Observer wiring
     ✓ <service-specific items>
   ```

6. **Checkpoint:** AskUserQuestion — proceed to planning, or stop here if the user wants to fix something.

## Stage 3 — Plan the Implementation

1. Dispatch `m2-feature-developer` with the verified spec table from Stage 1 passed in the prompt. The sub-skill should NOT re-parse the PDF — feed it the table.

2. `m2-feature-developer` produces a commit-by-commit plan. The orchestrator displays it in the main context.

3. **Checkpoint:** AskUserQuestion — approve the plan, refine it, or restart from Stage 1 (if the spec was misread).

## Stage 4 — Implementation (Per-Commit Checkpoints)

The orchestrator does NOT auto-implement. For each commit in the approved plan:

1. Surface the commit's intent: which files change, what the changes accomplish, why this is its own commit.
2. **Checkpoint:** AskUserQuestion — "ready to plan this commit, or skip / reorder?"
3. Once user says go, write the code changes via `Edit` / `Write`.
4. Show the user the exact commands to run:
   ```
   git add <specific files>
   git commit -m "<commit message per M2 conventions>"
   ```
   Do NOT run them yourself unless the user explicitly says "go ahead and commit."
5. Confirm the file changes match what the plan said.
6. Ask: "ready for the next commit, or pause?"

After all commits land:
- Run `mvn clean package -DskipTests` to confirm the build is clean.
- If the build fails, return to whichever commit introduced the break.

## Stage 5a — Test Case Design (Human-in-the-Loop)

Draft a test plan with cases organized into these categories:

- **Spec cases** — every step from the §10.x "Test scenario" subsection. Always included.
- **Boundary cases** — empty inputs, max-length strings, off-by-one on date ranges, page=0 vs page=1, boundary timestamps (start `T00:00:00` / end `T23:59:59.999`), pagination caps (size=100 vs size=101).
- **Auth & ownership cases** — missing token, malformed token, expired token, wrong role, ownership violation (S1-F12 / S3-F12), ADMIN bypass.
- **Cross-DB consistency cases** — for any feature that writes to >1 store: trigger the write, verify all stores reflect it; soft-dep cases (NoSQL down → graceful degradation if applicable).
- **Cache cases** — first call (miss), second call (hit, faster), TTL expiry simulation, write-then-read invalidation check, observer-driven invalidation match.
- **Idempotency cases** — for S3-F11: same rideId twice should not double-increment.
- **Error path cases** — every error code listed in the spec (400 invalid input, 404 not found, 401 unauthorized, 403 forbidden, 409 conflict where applicable).

Display the proposed cases as a checklist, grouped by category, with each case stating: input, expected status code, expected post-conditions (DB rows / cache keys / events to verify).

**Checkpoint:** AskUserQuestion — "any cases to add or remove before we run them?" The user can add domain-specific edge cases the orchestrator missed.

## Stage 5b — Test Execution (Delegated to feature-tester Agent)

1. Dispatch the `feature-tester` sub-agent (Agent tool with `subagent_type: "feature-tester"`) passing:
   - The resolved feature spec (Stage 1 table).
   - The approved test plan (Stage 5a).
   - A note that the agent should generate its own JWT token via login if needed.

2. The agent runs each case end-to-end against the live local stack: HTTP via `curl`, DB inspections via `redis-cli` / `mongosh` / `cqlsh` / `cypher-shell` / `psql`, and assertions per the test plan.

3. The agent returns a structured pass/fail report. Display it.

4. **Checkpoint:**
   - If any case FAIL → return to Stage 4 to fix. Surface the agent's "fix hint" alongside the failing case.
   - If user flags a PASS as suspicious → re-dispatch with extra coverage on that case.
   - If all PASS → proceed to 5c.

## Stage 5c — Cache Audit (Skill Dispatch)

Once functional tests pass, dispatch the `cache-audit` skill scoped to the affected endpoints. This catches caching-specific issues (TTL drift, missing invalidation paths, key-format violations) that case-by-case tests don't always surface.

Display PASS/FAIL per check.

**Checkpoint:** if anything fails → return to Stage 4.

## Stage 6 — PR Readiness

1. Dispatch the `pr-check` skill on the current branch. Display the PASS/FAIL report verbatim.

2. If the result is **READY**:
   - Surface the PR description (generated by `pr-check`) in a code block for the user to copy.
   - Tell the user the exact commands to run:
     ```
     git push origin <branch-name>
     gh pr create --title "<title>" --body-file <file>   # or paste in GitHub UI
     ```
   - Remind them: regular merge commit on GitHub (NOT squash); do NOT delete the branch after merge.

3. If the result is **NOT READY**:
   - List each FAIL with file/line and the spec section that mandates the rule.
   - Offer to return to Stage 4 to fix.

The orchestrator's job ends when the user has the push/PR commands. **Do NOT execute them.**

## Output Conventions

- After each stage, print a one-line summary of what just happened: "Stage N complete: <X result>. Moving to Stage N+1." This makes the conversation skimmable.
- If the user aborts at any checkpoint, leave their working tree exactly as-is (don't try to "clean up" by running git commands).
- If the user re-invokes the orchestrator on the same branch later, detect that an in-progress branch exists and ask whether to resume or restart.
