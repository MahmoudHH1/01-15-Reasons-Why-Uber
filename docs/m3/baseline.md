<!-- Loaded by: skills/verify-entity, skills/pr-check, skills/m3-orchestrator -->

# M1/M2 Baseline — Layered Architecture, CRUD, Code Style

These rules are stable across all milestones. The auto-grader tests them on every branch.

## Layered Architecture (STRICT)

```
Client Request
      ↓
┌─────────────┐
│ Controller  │  HTTP handling ONLY. No business logic.
│             │  Validate request, call service, return response.
└──────┬──────┘
       ↓
┌─────────────┐
│  Service    │  ALL business logic lives here.
│             │  Business rules, validation, orchestration.
└──────┬──────┘
       ↓
┌─────────────┐
│ Repository  │  Database operations ONLY.
│             │  JpaRepository interfaces, @Query methods.
└──────┬──────┘
       ↓
   Database
```

### Allowed / Forbidden per layer

**Controller layer**
- Allowed: call service methods, handle HTTP mapping, return responses, basic request validation.
- Forbidden: importing or injecting `Repository` classes directly; any `@Query` / `@Transactional` / `@Modifying` annotations; business logic.

**Service layer**
- Allowed: business logic, calling repository methods, validation, orchestration.
- Forbidden: importing `HttpServletRequest`, `@RequestMapping`/`@GetMapping`/`@PostMapping`, returning `ResponseEntity`, direct JDBC or `EntityManager` usage.

**Repository layer**
- Allowed: `JpaRepository` interface extension, `@Query` annotations, naming-convention methods, NoSQL repositories (`MongoRepository`, `ElasticsearchRepository`, etc.).
- Forbidden: business logic in default/custom methods. Repositories are interfaces only.

### Cross-service rules

- **Forbidden:** `@ManyToOne`, `@OneToMany`, `@ManyToMany` referencing entities from other services; importing entity classes from other service packages.
- **Required (M3):** `@FeignClient` interfaces in the `contracts/` Maven module for every cross-service read.
- **Required (M3):** Plain `Long` fields for FK columns that referenced another service (uber-m3.md:104). The DB column still exists; there is no JPA relationship across databases.
- **Required (M3):** RabbitMQ events for every cross-service write side-effect (uber-m3.md:2636).
- **Forbidden (M3):** No service opens a JDBC connection to another service's database — Critical Rule #1, "Zero tolerance" (uber-m3.md:2635).

### Relationship rules (intra-service)

- `@JsonIgnore` on the inverse side (the `List<>` / `Set<>` side) of all bidirectional relationships, to prevent infinite recursion.
- Intra-service `@ManyToOne` (e.g., `SavedAddress→User`, `DriverDocument→Driver`, `RideStop→Ride`, `PaymentCoupon→Payment`, `PaymentCoupon→Coupon`) all stay JPA-managed because both sides live in the same service's database (uber-m3.md:117).

## Package Structure (per service)

```
src/main/java/com/team01/uber/<service>/
├── controller/    # REST controllers
├── service/       # Business logic
├── repository/    # JpaRepository (PG) + NoSQL repositories
├── model/         # JPA entity classes + NoSQL document/node/row classes
├── dto/           # DTOs (with Builder where 5+ fields)
├── event/         # M2: EntityObserver, MongoEventLogger, EventFactory
├── adapter/       # M2: NoSQL → DTO adapters
├── security/      # M2: JwtConfigurationManager, JwtService, JwtAuthenticationFilter, AuthHandler chain
├── config/        # SecurityConfig, cache config, observer registration
├── feign/         # M3: @FeignClient interfaces (for outbound) — interfaces themselves live in `contracts/`
└── messaging/     # M3: RabbitMQ event configs, publishers, consumers
```

For payment-service, also add a `strategy/` package (DP-1 Strategy, S5-F12). The `feign/` and `messaging/` packages are M3 additions on top of M2's package layout.

## Entity Rules

- All entities use **auto-generated Long IDs**: `@GeneratedValue(strategy = GenerationType.IDENTITY)`.
- **JSONB columns:** `Map<String, Object>` with Hibernate JSONB annotations.
- **Enums:** stored as SQL ENUMs; `@Enumerated(EnumType.STRING)` in JPA.
- **Cross-service references (M3):** plain `Long` FK columns, NOT JPA-managed `@ManyToOne` (uber-m3.md:104).
- **Cross-service data access (M3):** Feign for reads, RabbitMQ for writes — never native SQL across services (uber-m3.md:2636).

## CRUD Baseline

CRUD operations (create, read by ID, read all, update, delete) are the **baseline for every entity** and do NOT count as features. The auto-grader **tests CRUD** and will not run feature tests without it.

- Implement **all CRUD for all entities** before any features.
- Each service needs CRUD for all its entities (e.g., payment-service needs CRUD for Payment, Coupon, AND PaymentCoupon).

## Repository Layer Conventions

- One `JpaRepository<Entity, Long>` interface per entity.
- **Naming-convention methods** for simple lookups (e.g., `findByEmail`).
- **Custom `@Query` with native SQL** for complex queries within the service's own DB. **Never** for cross-service joins (cross-service reads go through Feign — uber-m3.md:2636).
- `@Modifying` + `@Transactional` for UPDATE/DELETE queries.

## Code Style — Human-Like Code

- **Comments:** sparingly; only when the logic is genuinely non-obvious. Keep them short and natural.
- **No** excessive javadoc on every method.
- **No** auto-generated boilerplate comments ("This method does X").
- **No** commenting obvious code — let the code speak for itself.
- Variable/method names should be self-documenting.

## Health Endpoints

Each service exposes `GET /api/<entity>/health` returning `OK`. These endpoints are **public** (gateway bypasses JWT for them) — `/api/users/health`, `/api/drivers/health`, `/api/rides/health`, `/api/locations/health`, `/api/payments/health`.
