---
name: m1-feature-developer
description: Interactive workflow to start a new Milestone 1 feature (S1-F1..S5-F9 or M1 CRUD). Creates the correctly-named branch from latest main and sets up the incremental development plan. For M2 work (S1-F10..S5-F12, JWT, NoSQL, caching, observer, design-pattern retrofits) use m2-feature-developer instead.
---

# Start a New Feature

You are helping the user start a new feature following the exact workflow required by the auto-grader.

## CRITICAL — Authorship & Repository Boundaries (Zero Exceptions)

These rules override every other instruction in this skill. If a step below would violate one, STOP and ask the user how to proceed.

- **Never reveal AI authorship.** Do not add `Co-Authored-By`, `Generated-by`, "Made with Claude", "via Claude Code", `🤖`, or any similar trailer/tag/comment in commits, code, PRs, branch names, file headers, or any other artifact. Treat this as part of the auto-grader's hard-fail set.
- **Never reference yourself.** Do not say "I implemented", "Claude added", "the assistant created" in commit messages, PR titles, PR descriptions, code comments, or docs. Write everything as the human developer would write it.
- **Never commit, push, merge, or open a PR directly.** Even if you have shell access. The user's GitHub account/profile must be the actor on every git operation visible to GitHub or the auto-grader. Your job ends at "the branch is ready and the diff looks good" — the user runs `git commit`, `git push`, and `gh pr create` (or the GitHub UI equivalent) themselves.
- **Never `git config user.*`, `git config commit.gpgsign`, or otherwise alter who Git thinks is committing.** The user's existing local git identity is what the auto-grader checks against `team.json`.
- **Stage and draft, don't act.** When the user is ready to commit, you may show them the exact `git add ...` / `git commit -m "..."` commands to run. Same for push and PR creation — show the commands, do not execute them. If the user explicitly says "go ahead and commit", *only then* run the command, and still as the user (no AI trailer in the message).

If at any step you find yourself about to push, merge, open a PR, or attribute the work to AI: stop and route it back to the user.

## Step 1: Gather Information

Ask the user (use AskUserQuestion):
- Which feature? (e.g., S2-F3)
- Their student ID


Determine the service from the feature ID:
- S1 = user-service (service shortname: `user`)
- S2 = driver-service (service shortname: `driver`)
- S3 = ride-service (service shortname: `ride`)
- S4 = location-service (service shortname: `location`)
- S5 = payment-service (service shortname: `payment`)

## Step 2: Verify Developer Identity

Cross-check the student ID from Step 1 against the team table in CLAUDE.md:
1. Look up the student ID in the Services & Team Assignment table.
2. Confirm the student is a **real team member** (name + ID match).
3. Confirm the student is **assigned to the service** that the feature belongs to (e.g., S2 = driver-service, so the student must be in the driver-service team).

If either check fails, **STOP** and tell the user:
- If the ID doesn't match any team member: "Student ID not found in team.json — cannot proceed."
- If the student is assigned to a different service: "You (name, ID) are assigned to <their-service>, but this feature belongs to <feature-service>. Are you sure you want to proceed?"

Only continue after identity is confirmed. Echo back to the user:
```
Developer: <full name> (<student ID>)
Assigned service: <service-name>
Feature service: <service-name>
✓ Identity verified
```

## Step 3: Verify Clean State

Run `git status` to check for uncommitted changes. If there are changes:
- Warn the user
- Ask if they want to stash or commit first
- Do NOT proceed with dirty working directory

## Step 4: Create Branch

Run these commands:
```
git checkout main
git pull origin main
git checkout -b feat/<service-shortname>/<feature-ID>/<studentId>
```

Example: `git checkout -b feat/driver/S2-F3/55-25085`

## Step 5: Load Feature Spec

Ask the user to paste the feature spec (from the assignment PDF) into the terminal. Store this for later reference in the workflow. Alternatively you can also ask for a source PDF for the feature description and parse the relevant details from it.

**Error Code Extraction:** When reading the feature spec, identify the expected HTTP error code for error cases (e.g., 404, 400, 409). If the spec does not explicitly mention an error code, **STOP and ask the user** what error code to use before proceeding. Never assume an error code — it must come from the spec or the user.

## Step 6: Explore Dependencies

Before planning the implementation, scan the codebase to understand what already exists:

1. **Read the feature spec** carefully and identify every entity and service it references (including cross-service dependencies).

2. **Check your own service:**
   - Read all existing entity classes in `model/`
   - Read all existing repository interfaces in `repository/`
   - Read all existing services in `service/`
   - Read all existing controllers in `controller/`
   - Note which entities, repositories, services, and endpoints already exist.

3. **Check external dependencies:**
   - If the feature references entities or data from **other services** (e.g., a driver feature that needs user data, or a ride feature that needs driver/location data), check whether those entities and their CRUD endpoints exist in the codebase.
   - For each external dependency, report to the user:
     - What entity/endpoint from which service is needed
     - Whether it already exists in the codebase or not
   - **NEVER implement code in other services.** Only note missing dependencies so the user can coordinate with the responsible team members.

4. **Report findings** to the user before proceeding:
```
Dependency Check: <feature-ID>
──────────────────────────────
Own service (<service-name>):
  ✓ <Entity> entity exists
  ✓ <Entity>Repository exists
  ✗ <missing thing> — needs to be created

External dependencies:
  ✓ <other-service>: <Entity> exists, CRUD available
  ✗ <other-service>: <Entity> missing — notify <team-member(s)>
  (or: No external dependencies)
```

Wait for the user to acknowledge before proceeding to the plan.

## Step 7: Create Detailed Implementation Plan

Based on the feature spec and dependency check, create a **detailed plan** broken into individual commits. Each commit should be a small, logical, independently working step. A feature must NEVER be one-shotted in a single commit.

Analyze the feature and break it down into the specific files and changes needed. Then map those changes to commits following this progression:

1. **Model/Entity changes** (if any new entities or fields are needed)
2. **Repository layer** — specific @Query methods or naming-convention methods this feature requires
3. **Service layer** — specific business logic, validations, and orchestration
4. **Controller layer** — specific REST endpoint(s)
5. **Refinements** — edge cases, null handling, error responses, cleanup

**Mandatory feature conventions:**

- **Error codes:** Feature-specific error codes come from the spec (extracted in Step 5). Use `ResponseStatusException(HttpStatus.XXX, "message")` in the service layer.
- **@Valid on controllers:** If the feature adds new endpoints with `@RequestBody`, add `@Valid` on the parameter.

Present the plan as:

```
Implementation Plan: <feature-ID>
───────────────────────────────────
Branch: feat/<service>/<feature-ID>/<studentId>

Commit 1: feat(<service-name>): <specific description> (<ID>)
  Files: <exact files to create/modify>
  Changes:
    - <specific change 1>
    - <specific change 2>

Commit 2: feat(<service-name>): <specific description> (<ID>)
  Files: <exact files to create/modify>
  Changes:
    - <specific change 1>
    - <specific change 2>

... (3-5+ commits total)

When done: push and create PR with regular merge commit.
Do NOT delete the branch after merging.
```

Each commit description should be specific to what is actually being done (not generic like "add query methods" — instead "add findByDriverIdAndStatus query for ride lookup").

## Step 8: Confirm and Start

Ask the user to review the plan. Once they approve (or after incorporating feedback), tell them the branch is ready and start implementing commit by commit.

## Step 9: Test the Feature

After all commits are made, test the feature end-to-end:

1. **Build the service:**
   ```
   mvn clean package -DskipTests -pl <service-module> -am
   ```
   If the build fails, fix compilation errors before proceeding.

2. **Ensure the database is running:**
   - Check `docker ps` for the PostgreSQL container
   - If not running, start it with `docker compose up -d postgres` or equivalent

3. **Start the service:**
   ```
   cd <service-module>
   java -jar target/<service-jar>.jar &
   ```
   Wait for it to be healthy (`curl /api/<service>/health`).

4. **Run the test scenario from the feature spec:**
   - Execute each step from the spec's test scenario using `curl` commands
   - Verify each expected HTTP status code and response
   - If the feature requires cross-service data (e.g., rides table), create the necessary tables/data manually via `psql`

5. **Create and run your own additional test scenarios:**
   - Think about edge cases NOT covered by the spec's test scenario
   - Test boundary conditions (empty inputs, max values, duplicate data, etc.)
   - Test error paths: 404 for non-existent resources, 400 for invalid input, constraint violations
   - Test with unexpected but valid combinations (e.g., updating to the same status, concurrent-like scenarios)
   - If the feature involves status transitions, test all valid and invalid transitions
   - If the feature involves cross-service queries, test with empty tables, missing foreign keys, and multiple matching records

6. **Report results:**
   ```
   Test Results: <feature-ID>
   ──────────────────────────
   Spec scenario:
     Step 1: <description> → <expected> = <actual> ✓/✗
     Step 2: <description> → <expected> = <actual> ✓/✗
     ...

   Additional tests:
     <description> → <expected> = <actual> ✓/✗
     <description> → <expected> = <actual> ✓/✗
     ...

   Overall: PASS / FAIL
   ```

7. **Stop the service** after testing (`taskkill` or `kill` the java process).

If any test fails, debug and fix the issue, committing the fix as a separate commit (e.g., `fix(<service-name>): fix <description> (<studentId>)`).
