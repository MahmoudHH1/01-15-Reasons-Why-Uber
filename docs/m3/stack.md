<!-- Loaded by: skills/m3-orchestrator, skills/pr-check -->

# M3 Stack Pins

Carries over the M2 stack pins; M3 adds the api-gateway module + Spring Cloud BOM.

## Java + Spring

- **Java 25 / JDK 25** — Docker base image `eclipse-temurin:25.0.2_10-jdk` (per CLAUDE.md and `Dockerfile`).
- **Spring Boot 4.0.4** across services. user-service still on 4.0.3 — bump in a separate `chore` commit.
- **Spring Cloud BOM 2025.1.1** — required for Feign + Cloud Gateway (uber-m3.md:158, "spring-cloud-dependencies 2025.1.1"). The renamed gateway artifact `spring-cloud-starter-gateway-server-webflux` resolves under this BOM (uber-m3.md:1434 — the older `spring-cloud-starter-gateway` from the 2025.0.x train does not).

## Jackson

- **Dual dependency.** Jackson 3.x (`tools.jackson.*`) is on the Spring Boot runtime classpath. Jackson 2.x (`com.fasterxml.jackson.*`) is required **only** by Hibernate 7.2's JSONB FormatMapper — do not remove the 2.x deps.

## PostgreSQL

- **Pinned to `postgres:17`** in K8s StatefulSets (uber-m3.md:1627, `image: postgres:17`).
- **PG18 breaks Hibernate 7.2** — explicitly called out in uber-m3.md:2639: "PostgreSQL 17. Not PG18 — breaks Hibernate native query implicit cast operator resolution."

## Configuration

- `application.yml` everywhere — auto-grader requires YAML, no `application.properties` may remain.
- Per-service datasource is now `jdbc:postgresql://<svc>-postgres:5432/uberdb-<svc>s` (uber-m3.md:65–100). The shared `postgres:5432/uberdb` URL from M1/M2 is gone.

## NoSQL (carried over from M2 — shared instance per uber-m3.md §1.3)

| Store         | Image / version                  | Role                                                  |
|---------------|----------------------------------|-------------------------------------------------------|
| MongoDB       | `mongo:latest`                    | event/audit log per service (5 collections)           |
| Redis         | `redis:latest`                    | cache (256 MB cap, allkeys-lru)                        |
| Elasticsearch | `elasticsearch:8.19.12`           | driver-service full-text search (`drivers` index)     |
| Neo4j         | `neo4j:latest`                    | ride-service recommendations + interaction graph      |
| Cassandra     | `cassandra:latest`                | location-service GPS tracking time-series             |

## RabbitMQ

- `spring-boot-starter-amqp` per service that publishes/consumes (uber-m3.md:251).
- Connection: `host: rabbitmq`, `port: 5672`, `guest:guest` (uber-m3.md:262–266).

## Observability

- **Loki4J** appender — `com.github.loki4j:loki-logback-appender:2.0.0` (uber-m3.md:1796).
- **Prometheus** scraping — actuator must expose `prometheus,health,info` (uber-m3.md:1885).
- **Grafana** — `grafana:10.4.2` (uber-m3.md:2360); NodePort 30030.

## Stack memory budget (MiniKube)

Per uber-m3.md:1748–1758, total cluster memory budget targets ~6 GB on the default MiniKube profile. Per-pod caps: PG StatefulSets `512Mi` each, Spring Boot Deployments `768Mi` each, RabbitMQ `512Mi`, ES/Neo4j/Cassandra `768Mi` each, Mongo/Redis `512Mi`/`256Mi`. Going over the budget triggers pod evictions during grading.
