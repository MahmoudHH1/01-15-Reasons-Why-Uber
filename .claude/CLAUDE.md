# Uber Replica — Project Conventions

## Project Overview

This is an auto-graded university project (Architecture of Massively Scalable Applications, GUC Spring 2026). We are building a ride-hailing platform (Uber replica) as a **Maven multi-module Spring Boot** backend with 5 services sharing a single PostgreSQL database.

- **Milestone 1** = 15% of final grade, 45 features (9 per service)
- **Team** = 15 members, 3 per service
- **Stack:** Java 25, Spring Boot, Spring Data JPA, PostgreSQL, Docker, Maven
- **No frontend** — backend architecture only

## Session Setup — Identify the Developer

At the start of every conversation where code will be written, committed, or branches created, you MUST confirm who is currently developing. **Do not assume based on memory or prior conversations.**

Ask: "Which team member is working? (name or student ID)"

Then verify against the team table below and confirm back:
- Full name
- Student ID
- Assigned service

Use this student ID for **all** branch names and commit messages in the session. If the user tries to commit or branch without confirming identity first, stop and ask.

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
├── repository/    # JpaRepository interfaces
├── model/         # JPA entity classes
└── dto/           # Data transfer objects
```

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

- **NEVER** add features not explicitly stated in the M1 project description
- If an additional feature or helper seems needed to complete a described feature, **ALWAYS ask the human first** and get explicit approval
- **NEVER** make assumptions about requirements — consult the M1 description PDF
- When in doubt about any convention, ask before proceeding
- The description document is the single source of truth for what to implement

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
