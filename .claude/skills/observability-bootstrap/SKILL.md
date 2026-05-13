---
name: observability-bootstrap
description: Wire Loki4J + Prometheus + Grafana per uber-m3.md §11 — loki-logback-appender 2.0.0, per-service logback-spring.xml with the right MDC subset, correlation-ID OncePerRequestFilter, RabbitMQ-listener MDC pump, actuator (prometheus, health, info), Prometheus 5-job scrape config, 5 dashboard JSON files with ≥3 LogQL + ≥3 PromQL panels each (uber-m3.md:1894).
---

# Observability Bootstrap

You are wiring the observability stack: every service emits structured JSON logs to Loki, Spring Boot Actuator metrics to Prometheus, and Grafana renders both. K8s `monitoring/` namespace lives at uber-m3.md:1518 / 2340–2363.

## Sources of Truth (Read First)

1. **`docs/m3/observability.md`** — full Loki4J template, MDC table, log-point checklist, panel options.
2. **`docs/m3/uber-m3.md` §11** — original spec (lines 1787–1928).
3. **`docs/m3/k8s-manifests.md`** — `monitoring/` subdirectory + slice ownership (uber-m3.md:2553/2556/2559).

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/observability.md`, `docs/m3/k8s-manifests.md` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Slice ownership (uber-m3.md:2553/2556/2559)

- **S2-INFRA** owns Loki K8s.
- **S3-INFRA** owns Prometheus K8s + the 5-job `prometheus.yml`.
- **S4-INFRA** owns Grafana K8s + the dashboards ConfigMap aggregating all 5 service dashboards.
- Each per-service slice contributes its own `logback-spring.xml`, scrape job entry, and dashboard JSON.

## Step 1: Identity + Branch

```
git checkout main && git pull origin main
git checkout -b chore/M3/<scope>/observability/<studentId>
```

`<scope>` per the slice ownership above.

## Step 2: Service-side — `pom.xml` (uber-m3.md:1791–1798)

Add to each service:

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>2.0.0</version>
</dependency>
```

## Step 3: Service-side — `logback-spring.xml` (uber-m3.md:1813–1851)

Use the ride-service template from `docs/m3/observability.md` as the base. Each other service uses the same XML structure but **drops the MDC fields it does not populate** from the `<message><pattern>` block (uber-m3.md:1815).

Per-service MDC subset (uber-m3.md:1805–1812):

| Service | MDC keys |
|---|---|
| user-service | `correlationId`, `userId` |
| driver-service | `correlationId`, `driverId`, `rideId`, `routingKey` |
| ride-service | `correlationId`, `rideId`, `userId`, `driverId`, `paymentId`, `routingKey` |
| location-service | `correlationId`, `driverId`, `rideId`, `routingKey` |
| payment-service | `correlationId`, `paymentId`, `rideId`, `userId`, `routingKey` |

Loki URL: `http://loki.monitoring.svc.cluster.local:3100/loki/api/v1/push` (uber-m3.md:1821).

## Step 4: Service-side — MDC population (uber-m3.md:1853–1857)

- `correlationId` — populated by a `OncePerRequestFilter` reading the `X-Correlation-ID` header set by api-gateway. Filter must clear MDC in `finally`.
- RabbitMQ consumers — read `correlationId` from inbound `Message` header and `MDC.put` at the start of the listener method; clear in `finally`.
- Entity IDs — `MDC.put("rideId", id.toString())` immediately before the operation; `MDC.remove(...)` in `finally`.
- `routingKey` — set by RabbitMQ publishers and consumers to the routing key being processed.

## Step 5: Service-side — required log points (uber-m3.md:1859–1876)

Add `private static final Logger log = LoggerFactory.getLogger(<Class>.class);` and emit:

- Controller method entry/exit (INFO).
- Feign call before/success/exception (INFO/WARN).
- RabbitMQ publish (INFO), consume start (INFO), success (INFO), error (ERROR).
- Saga state transitions (S3 only): `"Ride {} transitioning {} → {}"` (INFO).
- DB write success (INFO).
- Slow operation warning (WARN) for operations exceeding a threshold.

Full message-format suggestions in `docs/m3/observability.md`.

## Step 6: Service-side — actuator (uber-m3.md:1880–1890)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "prometheus,health,info"
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

## Step 7: K8s — Loki (S2-INFRA owns, uber-m3.md:2553)

`k8s/monitoring/loki/`:
- ConfigMap with Loki config.
- PVC for log storage.
- StatefulSet (Loki is stateful) with `loki:2.9.4` (uber-m3.md:2343).
- Service named `loki` exposing 3100.

## Step 8: K8s — Prometheus (S3-INFRA owns, uber-m3.md:2556)

`k8s/monitoring/prometheus/`:
- ConfigMap with `prometheus.yml` containing **5 scrape jobs**, one per service. Each job's `static_configs.targets` is `<svc>-service:8080` and `metrics_path: /actuator/prometheus`.
- PVC.
- Deployment with `prom/prometheus:v2.51.2` (uber-m3.md:2348).
- Service named `prometheus` exposing 9090.

## Step 9: K8s — Grafana (S4-INFRA owns, uber-m3.md:2559)

`k8s/monitoring/grafana/`:
- Datasources ConfigMap pointing at Loki (`http://loki:3100`) and Prometheus (`http://prometheus:9090`).
- Dashboards ConfigMap embedding all 5 service dashboard JSON files.
- PVC.
- Deployment with `grafana/grafana:10.4.2` (uber-m3.md:2360).
- Service `type: NodePort, nodePort: 30030` exposing 3000.

## Step 10: Per-service dashboard JSON (uber-m3.md:1892–1894)

Each service ships **one dashboard JSON file** with **≥ 3 LogQL panels and ≥ 3 PromQL panels**. Panel options per uber-m3.md:1908–1942 (also in `docs/m3/observability.md`):

LogQL: error rate, correlation-ID trace, RabbitMQ event audit, Feign outcomes, saga transitions, slow operations.
PromQL: HTTP request rate, latency percentiles, JVM health, DB pool saturation, RabbitMQ message rate, Feign outcomes, endpoint top-N.

Pick at least 3 from each list.

## Step 11: Verify mode

- Each service has `loki-logback-appender:2.0.0` in `pom.xml`.
- Each service has `logback-spring.xml` with the right MDC subset.
- Each service has a correlation-ID `OncePerRequestFilter`.
- Each service's `application.yml` exposes `prometheus,health,info` via actuator.
- `k8s/monitoring/{loki,prometheus,grafana}/` directories present.
- `prometheus.yml` ConfigMap has 5 scrape jobs (one per service).
- 5 dashboard JSON files; each has ≥ 3 LogQL panels and ≥ 3 PromQL panels.
- Grafana Service is `NodePort 30030`.

## Constraints

- **Never reveal AI authorship.**
- **Never push, merge, or open a PR directly.**
- This skill produces the observability scaffolding. Each per-service `INFRA` slice contributes its own scrape job entry and dashboard JSON; this skill drafts the templates and the slice owner fills in the content.
