---
name: pr-check                                
description: Full pre-PR verification checklist. Run this before creating a pull request to catch all auto-grader-failing issues at once.
---

# Pre-PR Checklist

You are running a comprehensive verification before the user creates a PR. This catches all issues that would cause the auto-grader to deduct points or give zero credit.

## Checklist

Run each check in order. Do NOT skip any check.

### 1. Git Conventions

Check the current branch name and all commits on this branch (vs main):
- Branch matches `feat/<service>/<feature-ID>/<studentId>` pattern
- All commit messages match `<type>(<service-name>): <description> (<studentId>)` format, where `<type>` is one of: `feat`, `fix`, `refactor`, `chore`, `docs`, `test`
- Student ID is consistent across branch name and commits
- Student ID matches a member in `team.json`
- The service in the branch matches the member's assigned service
- Note that the name of the Service in the branch is just the name of the entity (e.g., `driver`), not the full service name (e.g., `driver-service`). The full service name is only used within the commit message, not the branch name.

### 2. Incremental Commits

Run `git log main..HEAD --oneline` and count commits.
- WARN if there is only 1 commit (features should have 3-5+ commits)
- Check that commits show a logical progression (repo → service → controller)

### 3. Layered Architecture

For the service being worked on, read all Java files in `controller/`, `service/`, `repository/`, and `model/` packages. Check each against these rules:

**Controller Layer** (`controller/` package)
- Allowed: Call service methods, handle HTTP mapping, return responses, basic request validation
- Forbidden: Importing or injecting Repository classes directly, any `@Query`/`@Transactional`/`@Modifying` annotations, business logic (calculations, conditional workflows, data transformations)

**Service Layer** (`service/` package)
- Allowed: Business logic, calling repository methods, data validation, orchestration
- Forbidden: Importing `HttpServletRequest`, `@RequestMapping`, `@GetMapping`, `@PostMapping` etc., returning `ResponseEntity`, direct JDBC or EntityManager usage

**Repository Layer** (`repository/` package)
- Allowed: `JpaRepository` interface extension, `@Query` annotations, method naming conventions
- Forbidden: Business logic in default/custom methods, should be interfaces only (not classes)

**Cross-Service Rules**
- Forbidden: `@ManyToOne`, `@OneToMany`, `@ManyToMany` referencing entities from other services, importing entity classes from other service packages
- Required: Plain `Long` fields for foreign keys, native SQL `@Query` for cross-table JOINs

**Relationship Rules**
- Required: `@JsonIgnore` on the inverse side (the `List<>`/`Set<>` side) of all bidirectional relationships

For each FAIL, report exactly which file and line violates which rule and suggest the fix.

### 4. Entity Compliance

For each entity in the service, verify it matches the spec:
- All fields present with correct types
- Correct JPA annotations (@Entity, @Id, @GeneratedValue, relationships)
- No extra fields beyond the spec


### 5. CRUD Completeness

For each entity in the service, verify that all 5 CRUD operations exist:
- **Create:** POST endpoint in controller, create method in service, save in repository
- **Read by ID:** GET endpoint with path variable, findById in service
- **Read all:** GET endpoint returning list, findAll in service
- **Update:** PUT endpoint, update logic in service
- **Delete:** DELETE endpoint, deleteById in service/repository

### 6. Build Verification

Run `mvn clean package -DskipTests` from the project root and check for compilation errors.

### 7. Code Style

Scan for code style issues:
- Excessive comments (javadoc on every method, obvious comments)
- Auto-generated boilerplate comments
- TODO/FIXME left in code
- Hardcoded test data in main source files

## Output Format

```
Pre-PR Check: <branch-name>
═══════════════════════════

1. Git Conventions     [PASS/FAIL]
2. Incremental Commits [PASS/WARN/FAIL]
3. Architecture        [PASS/FAIL]
4. Entity Compliance   [PASS/FAIL]
5. CRUD Completeness   [PASS/FAIL]
6. Build               [PASS/FAIL]
7. Code Style          [PASS/WARN]

Overall: READY / NOT READY for PR
```

For any FAIL, list the specific issues and fixes needed.
For WARN, note concerns but don't block.
