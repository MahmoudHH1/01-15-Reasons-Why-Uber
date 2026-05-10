---
name: kubernetes-bootstrap
description: Generate (or verify) the M3 MiniKube manifests per uber-m3.md §10 — k8s/{namespaces,secrets,configmaps,pvcs,statefulsets,deployments,services,api-gateway,monitoring}/, with StatefulSets for all 11 databases (5 PG + Mongo + Redis + ES + Neo4j + Cassandra + RabbitMQ), Deployments for the 5 services, NodePort 30080 for gateway and 30030 for Grafana, deploy-order script. Critical Rule #6 — "StatefulSet for all databases" (uber-m3.md:2640).
---

# Kubernetes Bootstrap

You are generating (or auditing) the K8s manifests that deploy the entire stack to a local MiniKube cluster. K8s is the **graded surface** — uber-m3.md:35: "all services and databases deploy to a local MiniKube cluster."

## Critical Rules anchored

- **#1 No cross-service JDBC** (uber-m3.md:2635) — enforced by per-service PG StatefulSets.
- **#5 PostgreSQL 17** (uber-m3.md:2639) — image must be `postgres:17`. PG18 breaks Hibernate.
- **#6 StatefulSet for all databases** (uber-m3.md:2640) — never plain `Deployment` for stateful infra.

## Sources of Truth (Read First)

1. **`docs/m3/k8s-manifests.md`** — full directory listing, ConfigMap shape, StatefulSet template, deploy order, NodePort table, slice ownership.
2. **`docs/m3/uber-m3.md` §10** — original spec (lines 1503–1784).
3. **`docs/m3/observability.md`** — monitoring stack (Loki/Prometheus/Grafana) deployed under `k8s/monitoring/`.

## Step 1: Identity + Branch

```
git checkout main && git pull origin main
git checkout -b chore/M3/<scope>/k8s/<studentId>
```

`<scope>` is the slice owner (per uber-m3.md:2546–2562 — e.g., `S1-INFRA` owns gateway + Mongo, `S5-INFRA` owns RabbitMQ + ES, etc.).

## Step 2: Directory tree (uber-m3.md:1505–1575)

Generate (or verify) the full `k8s/` tree:

```
k8s/
├── namespaces/namespace.yaml
├── secrets/{jwt,user-postgres,driver-postgres,ride-postgres,location-postgres,payment-postgres}-secret.yaml
├── configmaps/{user,driver,ride,location,payment}-service-configmap.yaml + gateway-configmap.yaml
├── pvcs/{user,driver,ride,location,payment}-postgres-pvc.yaml + {rabbitmq,mongo,redis,elasticsearch,neo4j,cassandra}-pvc.yaml
├── statefulsets/ — same 11 databases
├── deployments/{user,driver,ride,location,payment}-service-deployment.yaml
├── services/ — ClusterIP for each service + headless for each PG + 6 for the NoSQL stores
├── api-gateway/{gateway-deployment.yaml, gateway-service.yaml}   # NodePort 30080
└── monitoring/{loki,prometheus,grafana}/...                       # see observability-bootstrap
```

## Step 3: Namespace (uber-m3.md:1577–1586)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: uber
```

All `kubectl` commands use `-n uber`.

## Step 4: Per-service PG StatefulSet (uber-m3.md:1606–1657)

Use `docs/m3/k8s-manifests.md` §10.4 as the template. For each of the 5 services, generate:

- `secrets/<svc>-postgres-secret.yaml` with `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` (= `uberdb-<svc>s`).
- `pvcs/<svc>-postgres-pvc.yaml`.
- `statefulsets/<svc>-postgres-statefulset.yaml` — `image: postgres:17`, `serviceName: <svc>-postgres`, `resources.limits.memory: 512Mi`, `pg_isready` probe (uber-m3.md:1750).
- `services/<svc>-postgres-svc.yaml` — `clusterIP: None` (headless).

## Step 5: NoSQL StatefulSets (uber-m3.md:1748–1758)

| StatefulSet | Memory | Probe |
|---|---|---|
| `mongo` | 512Mi | `mongosh --eval "db.adminCommand('ping').ok"` |
| `redis` | 256Mi | `redis-cli ping` returns `PONG` |
| `elasticsearch` | 768Mi | `httpGet /_cluster/health?wait_for_status=yellow&timeout=1s` on 9200 |
| `neo4j` | 768Mi | `tcpSocket` on 7687 |
| `cassandra` | 768Mi (heap 256MB) | `cqlsh -e 'DESCRIBE KEYSPACES'` |
| `rabbitmq` | 512Mi | `rabbitmq-diagnostics -q ping` |

Each gets a Service (ClusterIP for the standard ports). RabbitMQ exposes 5672 + 15672.

## Step 6: Service Deployments (uber-m3.md:1659–1705)

Per service:

- `<svc>-service-configmap.yaml` with `SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_HOST`, all `FEIGN_<OTHER>_SERVICE_URL` entries (uber-m3.md:1597–1604).
- `<svc>-service-deployment.yaml` — `resources.limits.memory: 768Mi`; readiness on `/actuator/health` (initialDelay 30s, period 10s); liveness on same (initialDelay 60s, period 30s); JWT_SECRET env from secretKeyRef.
- `<svc>-service-svc.yaml` — `type: ClusterIP`. Only the gateway is exposed externally.

## Step 7: API Gateway resources

Owned by `S1-INFRA` per uber-m3.md:2550. Defer to `gateway-bootstrap` for the actual filter + routing config; this skill only generates the Deployment + NodePort Service stubs.

## Step 8: Monitoring stack

Owned across `S2-INFRA` (Loki), `S3-INFRA` (Prometheus), `S4-INFRA` (Grafana). Defer to `observability-bootstrap` for the actual ConfigMaps + dashboards; this skill only generates the directory tree under `k8s/monitoring/`.

## Step 9: Deploy-order script (uber-m3.md:1729–1742, 1762–1768)

Save to `k8s/scripts/deploy.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

kubectl apply -f k8s/namespaces/
kubectl apply -f k8s/secrets/
kubectl apply -f k8s/pvcs/
kubectl apply -f k8s/statefulsets/
kubectl apply -f k8s/configmaps/

for db in user-postgres driver-postgres ride-postgres location-postgres payment-postgres mongodb redis elasticsearch neo4j cassandra rabbitmq; do
  kubectl wait --for=condition=ready pod -l app=$db -n uber --timeout=180s
done

kubectl apply -f k8s/deployments/
kubectl apply -f k8s/services/
kubectl apply -f k8s/api-gateway/
kubectl apply -f k8s/monitoring/
```

## Step 10: Verify mode

- All 11 StatefulSets present (5 per-service PG + Mongo + Redis + ES + Neo4j + Cassandra + RabbitMQ). Critical Rule #6 — no plain `Deployment` for stateful infra.
- All PG StatefulSets pinned to `postgres:17` (Critical Rule #5).
- Memory caps + per-DB probes match the §10.8 table.
- Gateway Service is `NodePort 30080`. Grafana Service is `NodePort 30030` (uber-m3.md:2361).
- Every service Deployment has `envFrom: configMapRef: <svc>-service-configmap` and a JWT_SECRET secretKeyRef.
- Headless services exist for the 5 PG StatefulSets.
- Deploy-order script waits on **every** DB pod (uber-m3.md:1762).

## Constraints

- **Never reveal AI authorship.**
- **Never push, merge, or open a PR directly.**
- This skill does not generate the gateway filter logic or the observability dashboards — those are `gateway-bootstrap` / `observability-bootstrap`.
