# Dashboard Verification Runbook — Design

Date: 2026-05-18
Status: Draft (brainstorm output, pre-implementation)

---

## 0. Purpose & scope

End-to-end runbook for verifying the 5 M3 Grafana dashboards against `docs/m3/uber-m3.md` §11. Walks from MiniKube bring-up through per-panel trigger commands and pass/fail criteria, with three tiers of cluster bring-up so each service can be tested without booting the full stack. Also surfaces the 3 broken and 1 at-risk dashboards found during inspection, with copy-paste fix patches.

Out of scope: writing the dashboards themselves (already in `k8s/monitoring/grafana/dashboards/`); auto-grader test harness.

---

## 1. Overview & spec source

Single source of truth: `docs/m3/uber-m3.md` §11 (lines 1787–1928). The spec mandates:

> "Each of the 5 services has its own Grafana dashboard. Each dashboard has at minimum **3 LogQL panels** and **3 PromQL panels** chosen from the lists below. Five dashboard JSON files must be submitted (one per service)." — §11.2 line 1894

| LogQL panel options (§11.3) | PromQL panel options (§11.4) |
|---|---|
| 1. Error rate | 1. HTTP request rate |
| 2. Correlation ID trace | 2. HTTP latency percentiles |
| 3. RabbitMQ event audit | 3. JVM health |
| 4. Feign call outcomes | 4. DB connection pool (HikariCP) |
| 5. Saga state transitions | 5. Cache hit/miss (`cache_gets_total`) |
| 6. Slow operation warnings | 6. RabbitMQ throughput |

A panel passes verification when (a) its query references the correct service label, (b) the underlying metric/log line is emitted by running code, and (c) firing the trigger command produces a visible reading on the dashboard within one Prometheus scrape interval (15 s) for PromQL or one Loki batch (~5 s) for LogQL.

Companion docs read during this runbook (do NOT escalate to `spec-clause-finder` unless explicitly contradicted):

- `docs/m3/observability.md` — Loki4J, MDC fields, required log points, actuator config
- `docs/m3/feign-contracts.md` — per-service Feign client interfaces and saga pre-check call chain
- `docs/m3/saga-events.md` — routing keys for synthetic publishes
- `docs/m3/jwt-contract.md` — header forwarding for correlation trace panels

---

## 2. Stage A — Cluster bring-up (tiered)

Three tiers replace the original monolithic bring-up so a teammate working on one dashboard does not have to start every dependency.

### 2.1 Tier 0 — Always-on baseline (~4 min, apply once per session)

Shared infrastructure plus **user-service**. user-service joins the baseline because 4 of 5 services Feign-call it inside `JwtAuthenticationFilter` / `UserLoaderHandler` for every authenticated request — without it, panels appear empty for reasons unrelated to the service under test.

```bash
minikube start --cpus=6 --memory=8192 --disk-size=40g \
               --driver=docker --kubernetes-version=v1.30.0

eval $(minikube docker-env)
docker compose build --no-cache user-service api-gateway
# build others on-demand per tier

kubectl apply -f k8s/namespaces/
kubectl apply -f k8s/secrets/jwt-secret.yaml
kubectl apply -f k8s/secrets/user-postgres-secret.yaml
kubectl apply -f k8s/configmaps/user-service-configmap.yaml
kubectl apply -f k8s/pvcs/  # Redis, Mongo, RabbitMQ, user-postgres
kubectl apply -f k8s/statefulsets/redis-statefulset.yaml
kubectl apply -f k8s/statefulsets/mongo-statefulset.yaml
kubectl apply -f k8s/statefulsets/rabbitmq-statefulset.yaml
kubectl apply -f k8s/statefulsets/user-postgres-statefulset.yaml
kubectl apply -f k8s/services/redis-svc.yaml \
                -f k8s/services/mongo-svc.yaml \
                -f k8s/services/rabbitmq-svc.yaml \
                -f k8s/services/user-postgres-svc.yaml \
                -f k8s/services/user-service-svc.yaml
kubectl apply -f k8s/deployments/user-service-deployment.yaml
kubectl apply -f k8s/monitoring/
kubectl wait --for=condition=ready pod -l tier=database -n uber --timeout=300s
kubectl wait --for=condition=ready pod -l app=user-service -n uber --timeout=180s
kubectl wait --for=condition=ready pod -n monitoring --timeout=180s
```

Smoke gates (must all return success before moving to Tier 1):

```bash
GATEWAY=$(minikube ip):30080    # only set if api-gateway up; otherwise use port-forward
GRAFANA=$(minikube ip):30030

kubectl port-forward -n uber svc/user-service 8081:8080 &
curl -sf http://localhost:8081/api/users/health      # → "OK"
curl -sf http://$GRAFANA/api/health                  # → {"database":"ok"}
kubectl exec -n monitoring deploy/prometheus -- \
        wget -qO- http://localhost:9090/-/ready      # → "Prometheus Server is Ready."
```

### 2.2 Tier 1 — Pure solo (per service, ~1 min)

Apply only the service-under-test's deployment + its own PG + its service-specific NoSQL (if any). Feign clients to non-user downstreams will fail with `FeignException` — this is the **failure-side** of the Feign Call Outcomes panel.

| Service under test | Apply these in addition to Tier 0 |
|---|---|
| **user-service** | (already in Tier 0) |
| **driver-service** | driver-postgres-{secret,pvc,svc,statefulset}, elasticsearch-{pvc,svc,statefulset}, driver-service-{configmap,svc,deployment} |
| **ride-service** | ride-postgres-{secret,pvc,svc,statefulset}, neo4j-{pvc,svc,statefulset}, ride-service-{configmap,svc,deployment} |
| **location-service** | location-postgres-{secret,pvc,svc,statefulset}, cassandra-{pvc,svc,statefulset}, location-service-{configmap,svc,deployment} |
| **payment-service** | payment-postgres-{secret,pvc,svc,statefulset}, payment-service-{configmap,svc,deployment} |

### 2.3 Tier 2 — Solo + one Feign target (selective integration)

Add **one** downstream to green-line a specific Feign-target line on the panel. user-service is already in Tier 0, so the relevant additions are:

| Service under test | To turn green | Add |
|---|---|---|
| user-service | user→ride | ride-service (+ride-postgres+Neo4j) |
| user-service | user→payment | payment-service (+payment-postgres) |
| driver-service | driver→ride | ride-service |
| ride-service | ride→driver | driver-service (+driver-postgres+ES) |
| ride-service | ride→location | location-service (+location-postgres+Cassandra) |
| location-service | location→driver | driver-service |
| payment-service | payment→ride | ride-service |
| payment-service | payment→driver | driver-service |

### 2.4 Tier 3 — Full integration (the heavy path)

Apply everything. Required only for:

- Cross-service Correlation ID trace panels (one ID must appear in ≥2 services' logs)
- Feign Call Outcomes — **all-success** baseline reading
- Saga state transitions B/C (compensation cascade)

```bash
kubectl apply -f k8s/secrets/ -f k8s/configmaps/ -f k8s/pvcs/ \
              -f k8s/statefulsets/ -f k8s/services/ \
              -f k8s/deployments/ -f k8s/api-gateway/
kubectl wait --for=condition=ready pod -l tier=app -n uber --timeout=240s
```

### 2.5 The `up-tier.sh` helper

```bash
./scripts/up-tier.sh baseline                 # Tier 0
./scripts/up-tier.sh solo <service>           # Tier 1
./scripts/up-tier.sh feign <service> <target> # Tier 2
./scripts/up-tier.sh full                     # Tier 3
```

Script source lives at `scripts/up-tier.sh`. It is **idempotent** — re-running with the same args is a no-op.

---

## 3. Stage B — Seed data

### 3.1 `scripts/seed-baseline.sh`

Idempotent. Mints JWTs (signed with the secret in `k8s/secrets/jwt-secret.yaml`), inserts 3 users into user-postgres via `kubectl exec`, and emits `.env.dashboards`:

```env
GATEWAY_URL=http://192.168.49.2:30080
GRAFANA_URL=http://192.168.49.2:30030
USER1_TOKEN=eyJhbGc...
USER2_TOKEN=eyJhbGc...
ADMIN_TOKEN=eyJhbGc...
DRIVER1_TOKEN=eyJhbGc...
HAPPY_CORRELATION_ID=11111111-1111-1111-1111-111111111111
SAGA_CORRELATION_ID=22222222-2222-2222-2222-222222222222
DLQ_CORRELATION_ID=33333333-3333-3333-3333-333333333333
```

All Stage C trigger commands `source .env.dashboards` first.

### 3.2 Per-service seed scripts

| Script | Inserts | Used by panels |
|---|---|---|
| `scripts/seed-user.sh` | 3 users (active, deactivated, pending) | user-service Error / HTTP rate |
| `scripts/seed-driver.sh` | 3 drivers (online, offline, pending-doc), 5 ratings | driver Availability/Rating stats |
| `scripts/seed-ride.sh` | 5 rides — one per state REQUESTED/ACCEPTED/IN_PROGRESS/COMPLETED/CANCELLED | ride Error/RabbitMQ audit |
| `scripts/seed-location.sh` | 5 driver pings, 1 nearby snapshot | location Error/RabbitMQ audit |
| `scripts/seed-payment.sh` | 5 payments (initiated, captured, refunded, failed, voided) | payment RabbitMQ audit |
| `scripts/seed-saga.sh` | Cross-service consistent dataset for X1/X2 scenarios | Stage D only |

### 3.3 `scripts/fake-publish.sh`

For Tier 2 panels that need an upstream event without booting the upstream service. Talks to RabbitMQ directly:

```bash
./scripts/fake-publish.sh <exchange> <routingKey> \
   --rideId=42 --userId=1 --driverId=1 --fare=15.00 \
   --correlationId=$HAPPY_CORRELATION_ID
```

Implementation: `kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin publish exchange=<exchange> routing_key=<routingKey> payload='<json>' payload_encoding=string`. Headers are passed via `properties=...`.

Used heavily by the DLQ panel verification (publish a deliberately malformed payload, watch the listener retry 3× and dead-letter).

---

## 4. Stage C — Per-service panel verification

### 4.1 Per-panel block template

Every panel in §4.2–4.6 is documented with the same 5-field shape:

```
Panel <n>: <title>
  Spec ref:        uber-m3.md §11.<3|4> Panel <m> — "<spec name>"
  Current query:   <verbatim PromQL/LogQL from dashboard JSON>
  Compliance:      OK | AT_RISK (...) | BROKEN (...)
  Trigger:         <copy-pasteable command(s)>
  Expected:        <visible reading on the dashboard>
  Pass:            <objective check>
  Negative test:   <how to break it on purpose, if applicable>
```

The 36 panels (6+11+7+6+6) are tabulated below. Where multiple panels share an identical trigger family (e.g., "hit any endpoint 50×"), the trigger is documented once at the top of each subsection and referenced by name.

### 4.2 user-service dashboard (6 panels)

> **STATUS: BROKEN — every query references `service="ride-service"` / `job="ride-service"`.** See §7.1 for the patched JSON. The verification commands below assume the patch has been applied.

Required tier: Tier 1 (plus Tier 0 baseline). Seed: `seed-user.sh`.

Common trigger commands:

```bash
source .env.dashboards
kubectl port-forward -n uber svc/user-service 8081:8080 &

# Trigger A — generate HTTP volume + error mix
for i in $(seq 1 50); do
  curl -s -H "Authorization: Bearer $USER1_TOKEN" \
       -H "X-Correlation-ID: $HAPPY_CORRELATION_ID" \
       http://localhost:8081/api/users/$((RANDOM % 5 + 1)) >/dev/null
done

# Trigger B — force ERROR lines (request a non-existent user)
for i in 1 2 3; do
  curl -s -H "Authorization: Bearer $USER1_TOKEN" \
       http://localhost:8081/api/users/99999 >/dev/null
done
```

| # | Panel | Spec ref | Compliance (after §7.1 patch) | Trigger | Expected reading | Pass criteria | Negative test |
|---|---|---|---|---|---|---|---|
| 1 | Error Rate | §11.3 Panel 1 | OK | Trigger B | Time series rises within 5 s | Loki instant `count_over_time({service="user-service",level="ERROR"}[1m]) > 0` | Stop triggering → series returns to 0 within 1 min |
| 2 | RabbitMQ Event Audit by Routing Key | §11.3 Panel 3 | OK | `seed-user.sh` (publishes user.created) | `user.created` routingKey appears | Loki query for `routingKey="user.created"` returns ≥3 lines | — |
| 3 | Feign Call Outcomes | §11.3 Panel 4 | OK | Trigger A (user→ride summary) | Empty under Tier 1 (failure); green under Tier 2 + ride-service | Tier 1: `\|= "Feign call.*failed"` returns lines. Tier 2: `\|= "Feign call.*returned successfully"` returns lines | Scale ride-service to 0 → success line drops, failure line spikes |
| 4 | HTTP Request Rate per Endpoint | §11.4 Panel 1 | OK | Trigger A | One line per `uri`, > 3 req/s during Trigger A | `rate(http_server_requests_seconds_count{job="user-service"}[1m]) > 0` | — |
| 5 | HTTP Latency P99 | §11.4 Panel 2 | OK | Trigger A | P99 < 1 s under no-load, spikes under contention | `histogram_quantile(0.99, ...) returns a number > 0` | Hit `/api/users/{id}/ride-summary` (S1-F3, slowest Feign-enriched) and watch P99 climb |
| 6 | JVM Heap Usage | §11.4 Panel 3 | OK | (passive) | Heap line visible from cold start | `jvm_memory_used_bytes{job="user-service",area="heap"} > 0` | Drive heap up with `for i in {1..1000}; do curl ... done` — see growth before GC |

### 4.3 driver-service dashboard (11 panels)

Required tier: Tier 1 (Feign failures) or Tier 2 with ride-service for green-line. Seed: `seed-driver.sh`.

Common triggers:

```bash
source .env.dashboards
kubectl port-forward -n uber svc/driver-service 8082:8080 &

# Trigger A — generate driver-status churn (publishes driver.status-changed)
for i in $(seq 1 10); do
  curl -s -X PUT -H "Authorization: Bearer $DRIVER1_TOKEN" \
       -H "Content-Type: application/json" \
       -H "X-Correlation-ID: $HAPPY_CORRELATION_ID" \
       -d "{\"status\":\"ONLINE\"}" \
       http://localhost:8082/api/drivers/1/availability
  sleep 1
done

# Trigger B — DLQ via fake malformed event
./scripts/fake-publish.sh ride.events ride.completed \
  --invalid-payload --correlationId=$DLQ_CORRELATION_ID
```

| # | Panel | Spec ref | Compliance | Trigger | Expected | Pass | Negative |
|---|---|---|---|---|---|---|---|
| 1 | Error Rate | §11.3 Panel 1 | OK | Trigger A then hit `/api/drivers/99999` 3× | ERROR line spike | `count_over_time({service="driver-service",level="ERROR"}[1m]) > 0` | — |
| 2 | Feign Call Outcomes | §11.3 Panel 4 | OK | Trigger A under Tier 1 | `FeignException` series rises | `\|= "FeignException"` returns lines | Tier 2 + ride-service → `\|= "FeignException"` returns 0 |
| 3 | Correlation ID Trace | §11.3 Panel 2 | OK | Trigger A with fixed `$HAPPY_CORRELATION_ID` | Multi-line log stream filtered by that ID | `{service="driver-service"} \| json \| correlationId="$HAPPY"` returns ≥5 lines | — |
| 4 | Driver Availability Updates (stat) | not in §11.3 list — **AT_RISK** | AT_RISK — custom panel, not one of the 6 spec options | Trigger A | Stat panel shows 10 | Best-effort; spec compliance counted by the 3+3 panels above | — |
| 5 | Driver Rating Events (stat) | not in §11.3 list — **AT_RISK** | AT_RISK — custom | `seed-driver.sh` inserts 5 ratings | Stat shows 5 | Best-effort | — |
| 6 | HTTP Request Rate | §11.4 Panel 1 | OK | Trigger A | `rate(...{job="driver-service"}[1m]) > 0` | — | — |
| 7 | HTTP Latency p95 | §11.4 Panel 2 | OK | Trigger A | P95 line visible | — | — |
| 8 | JVM Heap Usage | §11.4 Panel 3 | OK | passive | Heap visible | — | — |
| 9 | RabbitMQ Publish Rate | §11.4 Panel 6 | **AT_RISK** — uses `rabbitmq_acknowledged_published_total` which is not a standard Spring AMQP metric | Trigger A | Panel may be empty | See §7.4 patch; replace with `spring_rabbit_template_seconds_count` | — |
| 10 | RabbitMQ Consumer Throughput | §11.4 Panel 6 | AT_RISK — uses `rabbitmq_consumed_total` (non-standard) | Trigger B repeated valid publishes | Panel may be empty | See §7.4 | — |
| 11 | RabbitMQ Listener Failures → DLQ | §11.4 Panel 6 | AT_RISK — uses `rabbitmq_rejected_total` (non-standard); Spring-AMQP exposes `spring_rabbit_listener_seconds_count{result="failure"}` instead | Trigger B (malformed payload, retried 3× → DLQ) | Failure series ticks | After §7.4 patch, `rate(spring_rabbit_listener_seconds_count{job="driver-service",result="failure"}[1m]) > 0` | — |

### 4.4 ride-service dashboard (7 panels)

> **STATUS: LogQL panel count short of spec.** Only panel 1 (Error rate) matches the spec's 6 LogQL options. Panel 2 (Postgres Event Actions piechart) is a log-level distribution, not an Error/Trace/RabbitMQ/Feign/Saga/Slow panel. Panel 3 (Live System Activity) is a raw stream, not a Correlation ID trace (no `correlationId` filter). See §7.3 for the patch that brings the dashboard to ≥3 spec-compliant LogQL panels.

Required tier: Tier 1 for solo, Tier 3 for saga. Seed: `seed-ride.sh`.

Common triggers:

```bash
source .env.dashboards
kubectl port-forward -n uber svc/ride-service 8083:8080 &

# Trigger A — drive HTTP and create RabbitMQ publishes
for i in $(seq 1 30); do
  curl -s -H "Authorization: Bearer $USER1_TOKEN" \
       -H "X-Correlation-ID: $HAPPY_CORRELATION_ID" \
       http://localhost:8083/api/rides/$((RANDOM % 5 + 1)) >/dev/null
done

# Trigger B — saga (Tier 3 only)
curl -X POST -H "Authorization: Bearer $USER1_TOKEN" \
     -H "X-Correlation-ID: $SAGA_CORRELATION_ID" \
     http://$GATEWAY_URL/api/rides/1/complete
```

| # | Panel | Spec ref | Compliance | Trigger | Expected | Pass | Negative |
|---|---|---|---|---|---|---|---|
| 1 | Ride Database Error Rate | §11.3 Panel 1 | OK | Hit non-existent ride 3× | Spike | `rate({service="ride-service"} \|= "ERROR" [1m]) > 0` | — |
| 2 | Postgres Event Actions Distribution | none — **BROKEN against spec** | BROKEN — not one of the 6 LogQL options | n/a | n/a | Patch per §7.3 to a Correlation ID trace panel | — |
| 3 | Live System Activity Log Stream | none — **BROKEN against spec** | BROKEN — raw stream, not a Correlation ID trace | n/a | n/a | Patch per §7.3 to a Feign Call Outcomes panel | — |
| 4 | HTTP Request Rate | §11.4 Panel 1 | OK | Trigger A | rate > 0 | — | — |
| 5 | HTTP Latency P50/P95/P99 | §11.4 Panel 2 | OK | Trigger A | P50/P95/P99 lines | — | Hit `/api/rides/driver/{id}/summary` (Feign-enriched) and watch P99 climb |
| 6 | RabbitMQ Publish vs Consume | §11.4 Panel 6 | OK — uses `spring_rabbit_*_seconds_count` (canonical Spring AMQP metric) | Trigger B | publish-rate line spikes; consume-rate matches | `rate(spring_rabbit_template_seconds_count{job="ride-service"}[1m]) > 0` | Disable RabbitMQ briefly → publish rate drops to 0 |
| 7 | Cache Hit / Miss Ratio | §11.4 Panel 5 | AT_RISK — requires Micrometer `CacheMetrics` binding registered for each named cache; verify with `curl -s http://localhost:8083/actuator/prometheus \| grep cache_gets_total` | Trigger A (same ride id twice) | Hit ratio rises toward 100% | `cache_gets_total{job="ride-service",result="hit"}` non-empty | Invalidate by PUT/DELETE then re-query → miss spikes |

### 4.5 location-service dashboard (6 panels)

Required tier: Tier 1. Seed: `seed-location.sh`.

| # | Panel | Spec ref | Compliance | Trigger | Expected | Pass | Negative |
|---|---|---|---|---|---|---|---|
| 1 | Error Rate Panel (Errors/min) | §11.3 Panel 1 | OK | `seed-location.sh` then hit `/api/locations/99999` | Spike | `count_over_time({service="location-service",level="ERROR"}[1m]) > 0` | — |
| 2 | Correlation ID Trace Panel | §11.3 Panel 2 | OK | `curl ... -H "X-Correlation-ID: $HAPPY"` 10× | Filtered log stream | `\| json \| correlationId=~".+"` returns lines | — |
| 3 | RabbitMQ Event Audit Panel (last hour) | §11.3 Panel 3 | OK | `./scripts/fake-publish.sh ride.events ride.placed ...` 5× | `routingKey="ride.placed"` time series | `sum by (routingKey) (count_over_time(...))` ≥ 5 | — |
| 4 | HTTP Request Rate | §11.4 Panel 1 | OK | Trigger A | rate > 0 | — | — |
| 5 | HTTP Latency P99 | §11.4 Panel 2 | OK | Trigger A | P99 visible | — | — |
| 6 | JVM Heap Usage | §11.4 Panel 3 | OK | passive | Heap visible | — | — |

### 4.6 payment-service dashboard (6 panels)

> **STATUS: PromQL panels use wrong label.** Panels 4/5/6 select `{service="payment-service"}` but Prometheus's scrape config (`k8s/monitoring/prometheus/prometheus-config.yaml`) emits `job="payment-service"`. PromQL panels will be empty. See §7.2 for the one-character patch (`service=` → `job=`).

Required tier: Tier 1. Seed: `seed-payment.sh`.

Common triggers:

```bash
source .env.dashboards
kubectl port-forward -n uber svc/payment-service 8085:8080 &

# Trigger A — drive HTTP + slow-op log lines
for i in $(seq 1 30); do
  curl -s -H "Authorization: Bearer $USER1_TOKEN" \
       http://localhost:8085/api/payments/user/1/summary >/dev/null
done

# Trigger B — fake ride.completed to populate RabbitMQ audit
./scripts/fake-publish.sh ride.events ride.completed \
  --rideId=42 --userId=1 --driverId=1 --fare=15.00
```

| # | Panel | Spec ref | Compliance (after §7.2 patch) | Trigger | Expected | Pass | Negative |
|---|---|---|---|---|---|---|---|
| 1 | Error rate | §11.3 Panel 1 | OK | Trigger A + bad id | Spike | `count_over_time({service="payment-service",level="ERROR"}[1m]) > 0` | — |
| 2 | RabbitMQ event audit (payment.* + ride.completed/cancelled) | §11.3 Panel 3 | OK | Trigger B | `routingKey="ride.completed"` line; subsequent `payment.initiated` publish line | `\| json \| routingKey=~"payment\\..*\|ride\\.(completed\|cancelled)"` returns ≥2 lines | — |
| 3 | Slow operation warnings (>1s) | §11.3 Panel 6 | OK | Drive S5-F10 revenue aggregation: `curl ... /api/payments/admin/revenue?byVehicleType=true` | WARN "Slow ... took ..." line | `\|~ "Slow .* took"` returns lines | — |
| 4 | HTTP request rate per endpoint | §11.4 Panel 1 | OK (after `service=` → `job=` patch) | Trigger A | rate > 0 | — | — |
| 5 | HTTP latency P50/P95/P99 | §11.4 Panel 2 | OK (after patch) | Trigger A | 3 series visible | — | — |
| 6 | JVM heap usage | §11.4 Panel 3 | OK (after patch) | passive | Heap line | — | — |

---

## 5. Stage D — Cross-cutting scenarios (Tier 3 only)

These scenarios populate panels that no single service can populate alone.

### X1 — End-to-end saga happy path

```bash
./scripts/up-tier.sh full
./scripts/seed-saga.sh
export CID=$HAPPY_CORRELATION_ID

curl -X POST -H "Authorization: Bearer $USER1_TOKEN" -H "X-Correlation-ID: $CID" \
     -H "Content-Type: application/json" \
     -d '{"pickup":"A","dropoff":"B","vehicleType":"STANDARD"}' \
     http://$GATEWAY_URL/api/rides
# accept, start, complete, pay — sequence in seed-saga.sh README
```

Populates: Correlation ID Trace (cross-service), Feign Outcomes (all-green), RabbitMQ audit (ride.placed → ride.accepted → ride.completed → payment.initiated → payment.captured).

### X2 — Saga compensation B/C

Same as X1 but use `$SAGA_CORRELATION_ID` and inject a payment-service failure:

```bash
kubectl set env -n uber deploy/payment-service FORCE_PAYMENT_FAIL=true
# then run X1; observe payment.failed → ride.refund-required cascade
kubectl set env -n uber deploy/payment-service FORCE_PAYMENT_FAIL-
```

Populates: Saga state transitions (ride-service log lines `Ride 42 transitioning COMPLETED → PAYMENT_FAILED → REFUNDED`).

### X3 — Cross-service Correlation ID trace

Run X1 with `$HAPPY_CORRELATION_ID`. Then in Grafana Explore, query `{app="uber"} | json | correlationId="$HAPPY_CORRELATION_ID"` (no service filter). Pass: ≥5 lines from at least 3 distinct `service` labels.

### X4 — Cache eviction cycle

Tier 1 sufficient. `GET /api/rides/1` 5× (hits build up), then `PUT /api/rides/1 ...` (write triggers invalidation), then `GET /api/rides/1` (miss). The Cache Hit/Miss panel on ride-service should show the dip.

### X5 — DLQ population

Tier 1 + `fake-publish.sh` malformed:

```bash
./scripts/fake-publish.sh ride.events ride.completed \
  --raw-payload='{"this":"is-not-a-valid-RideCompletedEvent"}'
# observe 3 retries → DLQ within ~30 s
kubectl exec -n uber rabbitmq-0 -- \
  rabbitmqadmin get queue=driver.ride.saga-listener.dlq count=5
```

Populates: RabbitMQ Listener Failures → DLQ panel (driver-service panel 11).

---

## 6. Stage E — Teardown

```bash
# Keep PVCs (cluster state) for next session:
kubectl delete deployment,statefulset --all -n uber
kubectl delete deployment,statefulset --all -n monitoring

# Or full reset:
minikube delete
```

---

## 7. Stage F — Remediation backlog

The dashboard inspection surfaced 4 issues that must be fixed before the auto-grader runs. Fix patches below are copy-pasteable.

### 7.1 user-dashboard.json — BROKEN, all queries reference ride-service

**File:** `k8s/monitoring/grafana/dashboards/user-dashboard.json`
**Symptom:** dashboard displays ride-service data instead of user-service data.
**Fix:** global replace inside the file (jq script, idempotent):

```bash
jq '(.panels[].targets[].expr) |= gsub("service=\"ride-service\""; "service=\"user-service\"")
    | (.panels[].targets[].expr) |= gsub("job=\"ride-service\""; "job=\"user-service\"")' \
   k8s/monitoring/grafana/dashboards/user-dashboard.json \
   > /tmp/user-dashboard.json && \
   mv /tmp/user-dashboard.json k8s/monitoring/grafana/dashboards/user-dashboard.json
```

Verification: `grep -c 'ride-service' k8s/monitoring/grafana/dashboards/user-dashboard.json` returns 0.

### 7.2 payment-dashboard.json — PromQL label key wrong

**File:** `k8s/monitoring/grafana/dashboards/payment-dashboard.json`
**Symptom:** panels 4/5/6 select `{service="payment-service"}` but Prometheus emits `job="payment-service"` (no `service` label).
**Fix:**

```bash
sed -i 's/service=\\\"payment-service\\\"/job=\\\"payment-service\\\"/g' \
    k8s/monitoring/grafana/dashboards/payment-dashboard.json
```

(LogQL panels 1/2/3 keep `service=` — that's a Loki label, set by the Loki4J appender per §11.1, and is correct.)

### 7.3 ride-dashboard.json — only 1 of 3 LogQL panels matches spec options

**File:** `k8s/monitoring/grafana/dashboards/ride-dashboard.json`
**Symptom:** panels 2 (level-distribution piechart) and 3 (raw log stream) are not any of the 6 LogQL options in §11.3. Spec requires ≥3 LogQL panels from that list.
**Fix:** replace panel 2 with a Correlation ID Trace panel, panel 3 with a Feign Call Outcomes panel. Replacement queries:

```jsonc
// Panel 2 — Correlation ID Trace
{
  "id": 2, "title": "Correlation ID Trace", "type": "logs",
  "datasource": { "type": "loki", "uid": "${DS_LOKI}" },
  "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
  "targets": [{ "refId": "A",
    "expr": "{app=\"uber\", service=\"ride-service\"} | json | correlationId =~ \".+\"" }]
}

// Panel 3 — Feign Call Outcomes
{
  "id": 3, "title": "Feign Call Outcomes", "type": "timeseries",
  "datasource": { "type": "loki", "uid": "${DS_LOKI}" },
  "gridPos": { "h": 8, "w": 24, "x": 0, "y": 8 },
  "targets": [
    { "refId": "A", "legendFormat": "success",
      "expr": "sum(rate({app=\"uber\", service=\"ride-service\"} |= \"returned successfully\" [5m]))" },
    { "refId": "B", "legendFormat": "failed",
      "expr": "sum(rate({app=\"uber\", service=\"ride-service\"} |= \"FeignException\" [5m]))" }
  ]
}
```

### 7.4 driver-dashboard.json — RabbitMQ metric names unverified

**File:** `k8s/monitoring/grafana/dashboards/driver-dashboard.json`
**Symptom:** panels 9/10/11 reference `rabbitmq_acknowledged_published_total`, `rabbitmq_consumed_total`, `rabbitmq_rejected_total` — none of these are standard Spring AMQP Micrometer metrics. Standard Spring AMQP exposes:

- `spring_rabbit_template_seconds_count` — publishes via `RabbitTemplate`
- `spring_rabbit_listener_seconds_count{result="success"|"failure"}` — `@RabbitListener` invocations

**Probe to confirm:**

```bash
kubectl exec -n monitoring deploy/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/label/__name__/values' | \
  jq -r '.data[]' | grep -i rabbit
```

If the probe doesn't list the metrics the dashboard expects, replace with the canonical Spring metrics:

```jsonc
// Panel 9 — Publish rate (replaces rabbitmq_acknowledged_published_total)
{ "expr": "sum by (exception) (rate(spring_rabbit_template_seconds_count{job=\"driver-service\"}[5m]))",
  "legendFormat": "published/s" }

// Panel 10 — Consume rate (replaces rabbitmq_consumed_total)
{ "expr": "sum(rate(spring_rabbit_listener_seconds_count{job=\"driver-service\",result=\"success\"}[5m]))",
  "legendFormat": "consumed/s" }

// Panel 11 — Listener failures (replaces rabbitmq_rejected_total)
{ "expr": "sum by (exception) (rate(spring_rabbit_listener_seconds_count{job=\"driver-service\",result=\"failure\"}[5m]))",
  "legendFormat": "{{exception}}" }
```

(Custom `Driver Availability Updates` and `Driver Rating Events` stat panels — 4 and 5 — are not in the §11.3 list but the dashboard already has 3 spec-compliant LogQL panels — Error/Feign/Correlation — and 3+ spec-compliant PromQL panels after this patch, so the spec minimum is met. The stat panels are kept as informational extras.)

---

## 8. Appendix

### 8.1 Feign caller → callee matrix (production code only)

| Caller | UserClient | DriverClient | RideClient | LocationClient | PaymentClient |
|---|---|---|---|---|---|
| user-service | — | — | ✓ | — | ✓ |
| driver-service | ✓ (incl. JwtFilter) | — | ✓ | — | — |
| ride-service | ✓ (incl. JwtFilter) | ✓ | — | ✓ | — |
| location-service | ✓ (incl. JwtFilter) | ✓ | — | — | — |
| payment-service | ✓ (incl. JwtFilter) | ✓ | ✓ | — | — |

### 8.2 Panel-solvability matrix per tier

| Panel category | T0 only | T1 (pure solo) | T2 (one Feign target) | T3 (full) |
|---|---|---|---|---|
| Error rate | — | ✓ | ✓ | ✓ |
| Correlation trace (single svc) | — | ✓ | ✓ | ✓ |
| Correlation trace (cross-svc) | — | — | — | ✓ |
| RabbitMQ audit (publish) | — | ✓ | ✓ | ✓ |
| RabbitMQ audit (consume) | — | with fake-publish | with fake-publish | ✓ |
| Feign — failure | — | ✓ | partial | partial |
| Feign — success | — | — | per target | ✓ |
| Saga A | — | ✓ | ✓ | ✓ |
| Saga B/C | — | — | — | ✓ |
| Slow operation | — | ✓ | ✓ | ✓ |
| HTTP rate / latency / JVM | — | ✓ | ✓ | ✓ |
| DB conn pool | — | ✓ | ✓ | ✓ |
| Cache hit/miss | — | ✓ | ✓ | ✓ |
| RabbitMQ throughput | — | publish only | ✓ | ✓ |
| DLQ | — | with fake-publish | with fake-publish | ✓ |

### 8.3 Spec line index

| Concept | uber-m3.md line |
|---|---|
| Dashboard count + panel minimum | 1894 |
| LogQL panel options | 1908–1924 |
| PromQL panel options | 1939–1955 |
| Loki4J appender config | 1789–1851 |
| Per-service MDC fields | 1801–1812 |
| Required log points | 1859–1876 |
| Actuator config | 1880–1890 |
| Monitoring stack images | 2340–2363 |
| K8s namespaces (uber + monitoring) | 1977– |
| Slice INFRA ownership | 2553, 2556, 2559 |
