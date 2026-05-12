# Skills & Agents — Quick Reference (M3)

Short index of what's in `.claude/`. Skills are workflows you invoke (`/<name>` or "use the X skill"); agents are subagents Claude dispatches via the `Agent` tool or you call with `@<name>`.

## Skills (in `.claude/skills/`)

12 active skills. The **M3 orchestrator is the default starting point** for any vertical slice (S1-READ-DB..S5-INFRA per uber-m3.md §13.2); the other skills are dispatched by it or used standalone for targeted work.

### Top-level entry point

| Skill | When to use | Why |
|---|---|---|
| **m3-orchestrator** | Default starting point for any M3 deliverable (S1-READ-DB..S5-INFRA) | End-to-end pipeline with human checkpoints: verifies every prerequisite (per-service PG, JWT at gateway, Observer, Feign, RabbitMQ, K8s, observability), reads the M3 spec verbatim via `spec-clause-finder`, plans commits as a vertical slice (Java + K8s + observability per uber-m3.md:2538), walks implementation, runs cache-audit + saga-validator (when applicable) + pr-check, leaves the branch ready for the user to push |

### M3-new wiring skills (one per architectural concern)

| Skill | When to use | Why |
|---|---|---|
| **db-isolation-bootstrap** | Migrating a service from shared `uberdb` to its own `uberdb-<svc>s` | Per-service PG datasource (uber-m3.md §1), flattens cross-service `@ManyToOne` to plain `Long` (uber-m3.md:104), removes any cross-DB native `@Query` JOIN, generates the `<svc>-postgres` StatefulSet stub. Critical Rule #1 — "No cross-service JDBC. Zero tolerance." |
| **feign-bootstrap** | Wiring synchronous cross-service reads | OpenFeign dep + Spring Cloud BOM 2025.1.1 + `@EnableFeignClients` + `@FeignClient` interfaces declared in `contracts/` (uber-m3.md:2570) + correlation-ID interceptor + try-catch wrapping + N+1 candidate cap of 100 (uber-m3.md:380). Includes the three S3-F4 saga pre-check Feign calls. Critical Rule #2 — "Feign for reads." |
| **rabbitmq-bootstrap** | Wiring asynchronous cross-service events | `spring-boot-starter-amqp` + `spring.rabbitmq.*` config (auto ACK, default-requeue-rejected: false, max-attempts: 3) + per-service `TopicExchange` + consumer queues with `x-dead-letter-exchange` + state-guarded idempotent `@RabbitListener` handlers + publish-after-commit semantics (no outbox). Critical Rules #3, #4, #11. |
| **gateway-bootstrap** | Adding the `api-gateway` 7th Maven module | Spring Cloud Gateway reactive WebFlux, `JwtGatewayFilter` as a `GlobalFilter` (NOT `OncePerRequestFilter`), 5 route predicates, `/api/auth/**` bypass, `X-User-Id`/`X-User-Role`/`X-Correlation-ID` forwarding to downstream services, NodePort 30080. Critical Rule #8 — "JWT validation at gateway." |
| **kubernetes-bootstrap** | Generating the MiniKube manifest tree | `k8s/{namespaces,secrets,configmaps,pvcs,statefulsets,deployments,services,api-gateway,monitoring}/` with 11 StatefulSets (5 PG + Mongo + Redis + ES + Neo4j + Cassandra + RabbitMQ), 5 service Deployments, NodePort 30080 for gateway and 30030 for Grafana, deploy-order script that waits on every DB pod. Critical Rule #6 — "StatefulSet for all databases." |
| **observability-bootstrap** | Wiring Loki4J + Prometheus + Grafana | `loki-logback-appender:2.0.0` + per-service `logback-spring.xml` with the right MDC subset + correlation-ID `OncePerRequestFilter` + RabbitMQ-listener MDC pump + actuator (`prometheus,health,info`) + Prometheus 5-job scrape config + Grafana dashboards ConfigMap. Each service ships a dashboard JSON with ≥3 LogQL + ≥3 PromQL panels (uber-m3.md:1894). |
| **saga-validator** | After implementing the saga (S3-F4, S3-F7, or any `ride.*`/`payment.*` consumer) | Runs the three test scenarios from uber-m3.md §8.6 end-to-end against the live MiniKube cluster: A (happy path), B (5-hop async compensation cascade with "Poll until status=REFUNDED, or wait ≥3s"), C (pre-check failure aborts before any event published). Read-only; structured PASS/FAIL. |


## Agents (in `.claude/agents/`)

Invoke either by typing `@<name>` in your prompt, or by asking Claude to dispatch via the `Agent` tool.

| Agent | When to use | Why |
|---|---|---|
| **spec-clause-finder** | Anytime you need the literal text of a clause, table, or test scenario from the spec | Defaults to **M3** (`docs/m3/uber-m3.md`); pass `--milestone m2` to read `Uber_descriptionM2.pdf` for the original M2 invariants. Returns verbatim quote + section + line/page citation. Avoids paraphrasing drift on grader-checked details (saga payloads, action vocabularies, routing keys, error codes). |
| **endpoint-cataloger** | Before/after a refactor pass, or before a release-readiness review | Walks the api-gateway + 5 services, produces a single table of every endpoint with its M3 compliance state — gateway route, JWT applied, cache key + TTL, observer events emitted, **Feign clients fanned out**, **RabbitMQ events published/consumed**, design patterns wired, distinct-path coexistence. Includes a Saga Participant Matrix block. Heavier, run sparingly. |
| **feature-tester** | Retro-coverage on an already-merged feature, or pre-merge gut-check | Runs the spec test scenario plus auto-generated boundary / auth / cross-DB / cache / idempotency / **saga A/B/C** / error cases against the live stack. Defaults to MiniKube (`http://$(minikube ip):30080`); falls back to docker-compose via env vars. Returns structured PASS/FAIL with fix hints. Cleans up its own test data. NOT for orchestrator-driven runs (that flow uses persisted scripts via Bash). |

## Companion Docs (in `docs/m3/`)

These are the data the skills/agents reference. Keep them in sync with code as M3 progresses:

**M2 carry-overs:**
- [cache-matrix.md](../docs/m3/cache-matrix.md) — every cached + invalidated endpoint (caching unchanged per uber-m3.md:43)
- [event-actions.md](../docs/m3/event-actions.md) — Mongo action vocabulary + the new RabbitMQ §2.9 routing keys
- [design-patterns.md](../docs/m3/design-patterns.md) — all 7 patterns with locations + grader hooks (unchanged per uber-m3.md:40)
- [yaml-fragments/](../docs/m3/yaml-fragments/) — per-service `application.yml` reference (now with Feign + RabbitMQ + actuator blocks)

**M3-only:**
- [saga-events.md](../docs/m3/saga-events.md) — choreography saga topology, payloads, A/B/C scenarios
- [feign-contracts.md](../docs/m3/feign-contracts.md) — Feign clients in `contracts/`, error handling, N+1 cap
- [k8s-manifests.md](../docs/m3/k8s-manifests.md) — full K8s tree, ConfigMaps, StatefulSets, deploy order
- [observability.md](../docs/m3/observability.md) — Loki4J + Prometheus + Grafana, MDC keys, panel options
- [jwt-contract.md](../docs/m3/jwt-contract.md) — gateway primary validator + service-side defense-in-depth
- [m2-carryover.md](../docs/m3/m2-carryover.md) — M2 behaviors still graded (Strategy boundary, ownership, dashboard-on-cache-hit, distinct-endpoint, etc.)
- [stack.md](../docs/m3/stack.md) — Java 25, Spring Boot 4.0.4, Spring Cloud BOM 2025.1.1, PG 17 pin
- [baseline.md](../docs/m3/baseline.md) — M1 layered architecture + CRUD + code style baseline

The original M3 spec lives at [uber-m3.md](../docs/m3/uber-m3.md) (2645 lines, markdown).

### M2 carry-over skills (still graded — in-place updated for M3)

| Skill | When to use | Why |
|---|---|---|
| **nosql-bootstrap** | Wiring or verifying a service's NoSQL clients | NoSQL stores carry over from M2 unchanged (uber-m3.md:41); they remain a shared instance per uber-m3.md §1.3 even though PG is now per-service. Two modes — `bootstrap` creates pom deps + yml + skeletons; `verify` audits against the spec. |
| **observer-bootstrap** | Wiring the GoF Observer chain | Carries over from M2 unchanged (uber-m3.md:44). Coexists with M3's RabbitMQ event surface — when both layers fire on the same write, the RabbitMQ consumer must be state-guarded for idempotency (uber-m3.md:2645). Skill enforces the no-`@EventListener`-to-Mongo and no-`new <Event>(...)`-outside-Factory rules. |
| **cache-audit** | After retrofits land; before any caching-touching PR | Caching invariants carry over from M2 unchanged (uber-m3.md:43). Verifies all 37+ cached endpoints, TTLs, key formats, and invalidation paths against `docs/m3/cache-matrix.md`. Probes Redis live. |
| **verify-entity** | Verifying entity definitions match the spec | M1 entity rules + the M3 cross-service `@ManyToOne`→`Long` rule (uber-m3.md:104) + the new Ride saga statuses (`PAYMENT_PENDING`, `PAID`, `PAYMENT_FAILED`, `REFUNDED` per uber-m3.md:46–55). |
| **pr-check** | Before opening any PR | Single merged checklist — git conventions, layered architecture, CRUD, build, AI-authorship scan, plus M2 carry-over checks (JWT defense-in-depth, caching, observers, design patterns, application.yml) and M3-new gates (per-service PG datasource, Feign in `contracts/`, RabbitMQ DLQ topology, idempotent consumers, gateway reactive filter, K8s + observability slice artifacts). |

## Recommended Order for the Team

1. Read [CLAUDE.md](CLAUDE.md) — short overview + branch/commit rules + team table.
2. Day-0: team lead lands the `contracts/` Maven module + `api-gateway/application.yml` stub + `prometheus-configmap.yaml` stub + `grafana-dashboards.yaml` ConfigMap stub on `main` (uber-m3.md:2596).
3. For each of the 15 vertical slices, the slice owner runs **`m3-orchestrator`** — it walks the entire pipeline (spec → prereqs → plan → implement → test → cache-audit → saga-validator → pr-check) with checkpoints.
4. The orchestrator dispatches `db-isolation-bootstrap` / `feign-bootstrap` / `rabbitmq-bootstrap` / `gateway-bootstrap` / `kubernetes-bootstrap` / `observability-bootstrap` automatically when its prerequisite-verification stage detects a missing wiring.
5. The orchestrator runs `pr-check` at the end; you never invoke it directly unless you skip the orchestrator and build a slice manually.
6. After all 15 slices merge, the `S5-INFRA` owner runs `saga-validator` (also embedded in their slice's JUnit integration tests per uber-m3.md:2522) to sign off the saga end-to-end.

## Archived (in `.claude/skills/_archive/`)

Five M1/M2-pipeline skills moved out of the active suite. They are kept on disk because the M2 invariants they encode are still graded in M3 (uber-m3.md:38–44), but they are not surfaced as commands:

| Archived skill | Why archived | What replaced it |
|---|---|---|
| **m1-feature-developer** | M3 introduces zero new M1 features (uber-m3.md:39 — only refactor work on existing ones) | n/a |
| **m1-retrofit-runner** | M1 retrofits done; M3 retrofits are vertical slices, not surgical retrofits | n/a |
| **m2-feature-developer** | M3 features additionally touch K8s + observability per uber-m3.md:2538 ("No deliverable is purely Java, K8s, or YAML"); flow shape differs | The `m3-orchestrator` pipeline (or building a slice manually following its stages) |
| **m2-orchestrator** | M2 pipeline gates (NoSQL/JWT/Observer only) are insufficient for M3 vertical slices | **m3-orchestrator** |
| **jwt-bootstrap** | Primary validator moves to the gateway per Critical Rule #8 (uber-m3.md:2642); the M2 service-side servlet filter is now defense-in-depth and already wired in every service | **gateway-bootstrap** for the primary (reactive) validator |

If you genuinely need to consult an archived skill (e.g., to re-read the M2 cache-matrix bootstrap rationale), open it directly under `.claude/skills/_archive/`.
