# Excel vs. M2 PDF — Coverage Audit

I parsed every row of [Milestone2_task_allocation.xlsx](Milestone2_task_allocation.xlsx) (1 sheet, 19 rows, 15 member assignments) and cross-referenced it against a section-by-section sweep of [Uber_descriptionM2.pdf](Uber_descriptionM2.pdf). The Excel is a per-member task allocation, so it's a *delegation* artifact — but the M2 PDF still has explicit, graded requirements that are not surfaced as anyone's task. The omissions split into three buckets.

---

## What the Excel covers correctly

- 15 M2 features (S1-F10..S5-F12) — one owner each, with section + page citation.
- All 7 design patterns mapped to specific owners.
- §4.3 JWT retrofit (×5 services) and §4.4 Redis caching retrofit (×5 services).
- §4.4.4 NoSQL-writer invalidation for driver/ride/location, plus the Observer-driven invalidation in payment.
- §4.5 Observer + Builder + Adapter retrofits per service.
- §4.6 Payment retrofits (surgeFee, simulateFailure, REFUNDED Observer on M1 S5-F2).
- 5 Mongo event docs (AuthEvent, DriverEvent, RideEvent, LocationEvent, PaymentAuditEvent), the 1 ES doc, the Neo4j entities, the Cassandra entity.
- CC-1 (Ahmed Gamal), CC-2 (Welo5), CC-5 (Youssef Maged), CC-6 (×5 services).

---

## A — Hard omissions (would cost real points if no one picks them up)

| #  | M2 PDF requirement | Section / page | Currently in Excel? | Notes |
|----|--------------------|----------------|---------------------|-------|
| A1 | **§4.2 — Role enum retrofit (RIDER default + seed ≥1 ADMIN)** is a separate task from CC-2. The grader asserts `pg_enum` contains both `RIDER` and `ADMIN` and that registration defaults to RIDER. | §4.2 p. 14-15 | Partially — Welo5 owns "seed 1 ADMIN user" under CC-2, but §4.2 itself (default role on registration, never assign ADMIN at registration) is not assigned as a retrofit row. | Should be folded into Welo5's row or Ahmed Gamal's S1-F10 row. |
| A2 | **MongoEvent interface (§7.1.1)** — common interface implemented by all 5 concrete event classes; required for `EventFactory` to return `MongoEvent`. | §7.1.1 p. 26 | Not assigned. The Excel says "Own AuthEvent / DriverEvent / RideEvent / LocationEvent / PaymentAuditEvent" but no one owns the interface. | Add to S1-F10 owner (he creates AuthEvent first). |
| A3 | **Driver CRUD ES auto-sync via JPA `@PostPersist/@PostUpdate/@PostRemove`** (NOT inline ES calls in CRUD controllers). | §4.5 p. 20 | Ahmed Gasser owns "Driver CRUD auto-index retrofit" but the Excel doesn't surface the *implementation form* requirement. | The wording "auto-index retrofit" is fine on its own; flag as a code-review checkpoint, not a re-assignment. |
| A4 | **`@EventListener` is BANNED for Mongo writes** (§3.3 hard rule, graded). | §3.3 p. 8 | Not surfaced anywhere. | Could go into the cross-cutting README; not assignment-worthy on its own but worth calling out in the PR template. |
| A5 | **`JwtConfigurationManager` MUST NOT be a Spring bean** (graded by reflection — no `@Component`/`@Service`/`@Configuration`). | §3.6 p. 11 | Ahmed Gamal owns DP-5 Singleton but the "no Spring stereotype" hard rule isn't on his row. | Append to his row. |
| A6 | **Hard-dependency policy** — every Mongo call is mandatory. MongoDB must be healthy for service startup and operational for every event-logged action. | §3.3 p. 8, §6.3 p. 23 | Updated. | Overridden by user request: changed from soft to hard dependency. |
| A7 | **§3.5 Builder retrofit explicitly EXCLUDES S2-F8 and S3-F8** (they return entities). | §3.5 p. 10 | The Excel's Builder retrofit lists are correct (driver: F3/F6/F9 — F8 absent; ride: F3/F6/F9 — F8 absent), so this is implicitly handled. | OK — log it as verified, no action needed. |
| A8 | **`new AuthEvent(...)` / `new DriverEvent(...)` source-scan ban** — all event construction must go through EventFactory. | §3.7 p. 12 | Owners of EventFactory branches (Ziad, Youssef Malek, Youssef Maged, Seifo) own "EventFactory <X> branch" but the source-scan ban isn't surfaced. | Cross-cutting code-review rule. |
| A9 | **Per-service Adapter roster — every service needs a `MongoDocumentAdapter`** (§3.8 lists S1, S2, S3, S4, S5 all with MongoDocumentAdapter). | §3.8 p. 13 | Only payment-service explicitly owns "MongoDocumentAdapter → PaymentMethodDTO" (Seifo). The other 4 services own only their non-Mongo adapter (or, for S1, only `ObjectArrayDtoAdapter`). | Add `MongoDocumentAdapter` to S1, S2, S3, S4 owners' rows (likely the Adapter-owners on each service). |
| A10 | **M1 S5-F4 must write CREATED + COMPLETED audit events** (otherwise S5-F11 returns empty). | §4.5 p. 19-20 | Seifo (S5-F11 owner) owns "Observer retrofit on payment-service M1 writes: S5-F5 / S5-F7 + payment CRUD writes" — but **S5-F4 CREATED/COMPLETED is not on his row**. Seif Tarek Mostafa (S5-F12 owner) owns the surgeFee/simulateFailure retrofits to S5-F4 but not the CREATED/COMPLETED audit writes. | Assign S5-F4 CREATED + COMPLETED writes to Seifo (or Seif Tarek M.) — without them, S5-F11 silently fails. |
| A11 | **S3-F3 cached by request-body hash** (POST that's semantically read-only). | §4.4.1 p. 16 | Mohamed Khaled's row says "S3-F3 cached by request-body hash". | OK. |
| A12 | **Cassandra queries MUST include `driver_id` in WHERE** (partition-key requirement). | §7.4.1 p. 29 | Not surfaced. | Code-review checkpoint for Youssef Maged (Cassandra owner). |

---

## B — Per-feature gotchas the feature owner could miss

These are nested inside §10's per-feature steps. The Excel just cites the section header (`§10.X.Y, p. NN`); it doesn't pull forward the high-risk sub-steps.

| #  | Feature | Sub-step that's easy to miss | Section / page |
|----|---------|------------------------------|----------------|
| B1 | S1-F10 Register | Status code is **201**, not 200; duplicate email/phone returns **409**, not 400 | §10.1.1 p. 33 |
| B2 | S1-F11 Login | "User not found" returns **401**, NOT 404 (anti-enumeration) | §10.1.2 p. 35 |
| B3 | S1-F12 Activity | `size` capped at **100** (silent clamp); `page` default 0, `size` default 10; **403** (not 404) when caller ≠ owner | §10.1.3 p. 35-36 |
| B4 | S2-F11 Index | INDEXED event must include `source` ∈ {`explicit`, `auto_crud_create`, `auto_crud_update`}; DELETE emits `DRIVER_DELETED` not INDEXED | §10.2.2 p. 37 |
| B5 | S2-F12, S3-F10, S4-F10, S5-F10 (all dashboards) | DASHBOARD_VIEWED / ANALYTICS_VIEWED logging **must run on cache hits** — log step lives OUTSIDE the cache decorator | §10 universal |
| B6 | S2-F10 vs M1 S2-F1, S3-F10 vs M1 S3-F6, S5-F12 vs M1 S5-F2 | M1 endpoints **must coexist** with new M2 endpoints — do not overwrite | §10 distinct-path rules |
| B7 | S3-F11 Interaction | Idempotency lives in **Neo4j**, not PG (no M1 schema change); on idempotent short-circuit, do NOT emit `INTERACTION_RECORDED`; **400** if ride status ≠ COMPLETED | §10.3.2 p. 39-40 |
| B8 | S3-F12 Recommendations | uid-claim ownership check on `userId` query param (403 if neither owner nor ADMIN) | §10.3.3 p. 40 |
| B9 | S4-F11 GPS Event | **Dual write** — Cassandra AND Mongo must each succeed independently | §10.4.2 p. 42-43 |
| B10 | S4-F10 Analytics | `averageSpeed` reads from `metadata->>'speed'` JSONB key, not a column | §10.4.1 p. 42 |
| B11 | S5-F10 Vehicle Revenue | `surgeFee` fallback rule = **0.15 × amount** when JSONB key missing | §10.5.1 p. 44 |
| B12 | S5-F11 Methods | Group-by considers ONLY `action ∈ {COMPLETED, FAILED}` — explicitly excludes CREATED/REFUNDED/REFUND_DENIED/ANALYTICS_VIEWED | §10.5.2 p. 45 |
| B13 | S5-F12 Refund | NoRefundStrategy denial path: **audit event + cache invalidation BEFORE the 400 throw**; refund data lives in `transactionDetails` JSONB — **do not add a SQL column** | §10.5.3 p. 46 |

---

## C — Infra & cross-cutting items the grader literally checks

| #   | Requirement | Section / page | Status |
|-----|-------------|----------------|--------|
| C1  | **PostgreSQL pinned to `postgres:17`** (PG 18 breaks Hibernate 7.2) | §6.1 p. 23 | Implicit in CC-5 (Youssef Maged) but exact tag not on his row. Add literally. |
| C2  | **Per-DB memory caps verbatim**: Redis 256mb allkeys-lru; ES `Xms512m -Xmx512m`; Cassandra `MAX_HEAP_SIZE=512M, HEAP_NEWSIZE=128M`; Neo4j 512m heap | §6.2 p. 23 | Youssef Maged's row says "memory caps" generically — should pull these in verbatim because the grader runs `docker stats`. |
| C3  | **Healthcheck commands per DB** (mongosh ping, redis-cli ping, ES `/_cluster/health`, `neo4j status`, `cqlsh DESCRIBE KEYSPACES`) | §6.4 p. 24 | Not surfaced. Add to CC-5 row. |
| C4  | **Container names + credentials verbatim** (uber-mongo/redis/elasticsearch/neo4j/cassandra; root/rootpass; redispass; uberks; neo4j/neo4jpass) | §6.4 p. 24 | Not surfaced. Add to CC-5 row. |
| C5  | **Stack reaches healthy in ≤120 s; docker stats < 5 GB total**; **services boot in isolation** when only PG is up | §9.5 p. 32 | Not on any row as a verification step. |
| C6  | **EXACTLY 3 public endpoints** (register, login, 5×health). Anything else public = grading failure. | §9.1 p. 30 | The 5 JWT-retrofit owners each implicitly cover this for their service, but it's not stated as an audit checkpoint. |
| C7  | **Migrate from `application.properties` → `application.yml`** (the M1 services use `.properties`) | §6.5 p. 25 | Each CC-6 owner has "application.yml for X-service" — that's adequate. |
| C8  | **JWT secret ≥ 32 bytes Base64-decoded** (short readable strings throw `WeakKeyException`) | §5.2 p. 21 | Not surfaced. Add to Ahmed Gamal's row. |
| C9  | **JWT must include `uid` claim** (numeric User.id) for ownership checks in S1-F12, S3-F12 | §5.2 p. 22 | Not on Ahmed Gamal's row. The owners of S1-F12 (seifabdalla) and S3-F12 (Youssef Malek) implicitly need this but the JWT-issuer owner doesn't have it called out. |
| C10 | **Stateless sessions, CSRF disabled** | §5.4 p. 22 | Not surfaced — should be on Ahmed Gamal's SecurityConfig row. |
| C11 | **Soft-dep degradation test**: stop Redis, retry cached endpoint, must still return from PG | §4.4 test p. 19 | Not surfaced as a test step anywhere. |
| C12 | **Jackson dual-dependency** (3.x `tools.jackson.*` for Spring Boot, 2.x `com.fasterxml.*` for Hibernate JSONB FormatMapper) | §1 p. 4 | Not assigned. Folds into CC-6 owners or build owner. |

---

## Recommended Excel edits (compact)

If you want a minimal set of additions to make the Excel grader-complete:

1. **Ahmed Gamal (S1-F10 / SecurityConfig owner)**: append "JWT secret ≥32 bytes; `uid` numeric claim; stateless sessions + CSRF disabled; `JwtConfigurationManager` NOT a Spring bean (no stereotype annotations)".
2. **Welo5 (CC-2 owner)**: append "§4.2 role enum retrofit — registration defaults to RIDER; ADMIN never auto-assigned; `pg_enum` retains both values".
3. **seifabdalla (S1-F12 owner)**: append "Define common `MongoEvent` interface in §7.1.1 (implemented by all 5 event classes)".
4. **Each Adapter owner (S1, S2, S3, S4)**: append `MongoDocumentAdapter` to the Adapter list (currently only payment-service has it).
5. **Seifo (S5-F11 owner)**: append "M1 S5-F4 CREATED + COMPLETED audit-event writes (otherwise S5-F11 returns empty)".
6. **Youssef Maged (CC-5 owner)**: replace generic "memory caps" with the verbatim ES/Redis/Neo4j/Cassandra cap strings; add the 5 healthcheck commands; add "PG pinned to `postgres:17`"; add "stack reaches healthy ≤120 s; docker stats <5 GB; services boot when only PG is up".
7. **One row, anywhere cross-cutting** (or in the README): "Mongo writes go through `MongoEventLogger` only — `@EventListener → Mongo` is BANNED; all event construction goes through `EventFactory` (no `new <X>Event(...)`)".

The 13 per-feature sub-steps in section B are not Excel-row material — they're per-feature acceptance criteria. Best place for those is each feature's PR description / acceptance checklist, not the allocation sheet.

---

## Summary

The Excel is structurally sound — every M2 feature, every retrofit section header, and every design pattern has an owner. The gap is **specificity inside owners' tasks**, not unassigned territory. The biggest concrete risks are:

- **A2** (`MongoEvent` interface — required for the EventFactory contract)
- **A9** (`MongoDocumentAdapter` missing on 4 services — §3.8 explicitly lists it for all 5)
- **A10** (CREATED + COMPLETED writes for M1 S5-F4 — without them, S5-F11 silently returns empty data)
