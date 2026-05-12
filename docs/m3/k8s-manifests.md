<!-- Loaded by: skills/kubernetes-bootstrap, skills/m3-orchestrator, skills/pr-check -->

# M3 Kubernetes Manifests

Source: `docs/m3/uber-m3.md` §10 + §11 (monitoring directories) + §16 (critical rules).

## Critical rules (uber-m3.md:2640, 2635)

- **StatefulSet for all databases.** Never use plain `Deployment` for a stateful database. (Critical Rule #6)
- **No cross-service JDBC.** Each service connects only to its own PG instance. (Critical Rule #1, "Zero tolerance".)

## Directory structure (uber-m3.md:1505–1575)

```
k8s/
├── namespaces/
│   └── namespace.yaml              # namespace: uber
├── secrets/
│   ├── jwt-secret.yaml
│   ├── user-postgres-secret.yaml
│   ├── driver-postgres-secret.yaml
│   ├── ride-postgres-secret.yaml
│   ├── location-postgres-secret.yaml
│   └── payment-postgres-secret.yaml
├── configmaps/
│   ├── user-service-configmap.yaml
│   ├── driver-service-configmap.yaml
│   ├── ride-service-configmap.yaml
│   ├── location-service-configmap.yaml
│   ├── payment-service-configmap.yaml
│   └── gateway-configmap.yaml
├── pvcs/
│   ├── user-postgres-pvc.yaml
│   ├── driver-postgres-pvc.yaml
│   ├── ride-postgres-pvc.yaml
│   ├── location-postgres-pvc.yaml
│   ├── payment-postgres-pvc.yaml
│   ├── rabbitmq-pvc.yaml
│   ├── mongo-pvc.yaml
│   ├── redis-pvc.yaml
│   ├── elasticsearch-pvc.yaml
│   ├── neo4j-pvc.yaml
│   └── cassandra-pvc.yaml
├── statefulsets/
│   ├── user-postgres-statefulset.yaml
│   ├── driver-postgres-statefulset.yaml
│   ├── ride-postgres-statefulset.yaml
│   ├── location-postgres-statefulset.yaml
│   ├── payment-postgres-statefulset.yaml
│   ├── rabbitmq-statefulset.yaml
│   ├── mongo-statefulset.yaml
│   ├── redis-statefulset.yaml
│   ├── elasticsearch-statefulset.yaml
│   ├── neo4j-statefulset.yaml
│   └── cassandra-statefulset.yaml
├── deployments/
│   ├── user-service-deployment.yaml
│   ├── driver-service-deployment.yaml
│   ├── ride-service-deployment.yaml
│   ├── location-service-deployment.yaml
│   └── payment-service-deployment.yaml
├── services/
│   ├── user-service-svc.yaml           # ClusterIP
│   ├── user-postgres-svc.yaml          # headless
│   ├── driver-service-svc.yaml         # ClusterIP
│   ├── driver-postgres-svc.yaml        # headless
│   ├── ride-service-svc.yaml           # ClusterIP
│   ├── ride-postgres-svc.yaml          # headless
│   ├── location-service-svc.yaml       # ClusterIP
│   ├── location-postgres-svc.yaml      # headless
│   ├── payment-service-svc.yaml        # ClusterIP
│   ├── payment-postgres-svc.yaml       # headless
│   ├── rabbitmq-svc.yaml
│   ├── mongo-svc.yaml
│   ├── redis-svc.yaml
│   ├── elasticsearch-svc.yaml
│   ├── neo4j-svc.yaml
│   └── cassandra-svc.yaml
├── api-gateway/
│   ├── gateway-deployment.yaml
│   └── gateway-service.yaml            # type: NodePort (30080)
└── monitoring/
    ├── monitoring-namespace.yaml
    ├── loki/
    │   └── ... ConfigMap + PVC + StatefulSet + Service (named `loki`)
    ├── prometheus/
    │   └── ... ConfigMap (5-job scrape config) + PVC + Deployment + Service (named `prometheus`)
    └── grafana/
        └── ... datasources ConfigMap + dashboards ConfigMap + PVC + Deployment + NodePort Service (30030)
```

## Namespace (uber-m3.md:1577–1586)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: uber
```

All `kubectl` commands use `-n uber`.

## ConfigMap example — ride-service (uber-m3.md:1588–1604)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ride-service-configmap
  namespace: uber
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://ride-postgres:5432/uberdb-rides
  SPRING_DATASOURCE_USERNAME: user
  SPRING_RABBITMQ_HOST: rabbitmq
  FEIGN_USER_SERVICE_URL: http://user-service:8080
  FEIGN_DRIVER_SERVICE_URL: http://driver-service:8080
  FEIGN_LOCATION_SERVICE_URL: http://location-service:8080
  FEIGN_PAYMENT_SERVICE_URL: http://payment-service:8080
```

## StatefulSet — per-service PostgreSQL (uber-m3.md:1606–1657, ride-postgres example)

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: ride-postgres
  namespace: uber
spec:
  serviceName: ride-postgres
  replicas: 1
  selector:
    matchLabels:
      app: ride-postgres
  template:
    metadata:
      labels:
        app: ride-postgres
    spec:
      containers:
        - name: postgres
          image: postgres:17
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: ride-postgres-secret
                  key: POSTGRES_USER
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ride-postgres-secret
                  key: POSTGRES_PASSWORD
            - name: POSTGRES_DB
              valueFrom:
                secretKeyRef:
                  name: ride-postgres-secret
                  key: POSTGRES_DB
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 1Gi
```

The other 4 PG StatefulSets are structurally identical; only `metadata.name`, `serviceName`, `app: <svc>-postgres` label, and the `secretKeyRef.name` change.

## Deployment — Spring Boot service (uber-m3.md:1659–1705, ride-service example)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ride-service
  namespace: uber
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ride-service
  template:
    metadata:
      labels:
        app: ride-service
    spec:
      containers:
        - name: ride-service
          image: <your-registry>/ride-service:latest
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: ride-service-configmap
            - secretRef:
                name: ride-postgres-secret
          env:
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: jwt-secret
                  key: jwt-secret
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 30
```

## API Gateway NodePort Service (uber-m3.md:1707–1727)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api-gateway
  namespace: uber
spec:
  type: NodePort
  selector:
    app: api-gateway
  ports:
    - port: 8080
      targetPort: 8080
      nodePort: 30080
```

Access the platform via: `curl http://$(minikube ip):30080/api/rides`

All other services use `type: ClusterIP`. **No service other than the gateway is reachable from outside the cluster.**

## Resource limits & probes (uber-m3.md:1748–1758)

| StatefulSet | `resources.limits.memory` | Liveness/readiness probe |
|---|---|---|
| `<svc>-postgres` ×5 | `512Mi` | `exec: pg_isready -U postgres` — `initialDelaySeconds: 15`, `periodSeconds: 10` |
| `mongodb` | `512Mi` | `exec: mongosh --quiet --eval "db.adminCommand('ping').ok"` — `initialDelaySeconds: 20` |
| `redis` | `256Mi` | `exec: redis-cli ping` (must return `PONG`) — `initialDelaySeconds: 10`, `periodSeconds: 5` |
| `elasticsearch` | `768Mi` | `httpGet: /_cluster/health?wait_for_status=yellow&timeout=1s` on port 9200 — `initialDelaySeconds: 60` |
| `neo4j` | `768Mi` | `tcpSocket` on port 7687 — `initialDelaySeconds: 30` |
| `cassandra` | `768Mi` (heap 256MB inside) | `exec: cqlsh -e 'DESCRIBE KEYSPACES'` — `initialDelaySeconds: 60`, `periodSeconds: 30` |
| `rabbitmq` | `512Mi` | `exec: rabbitmq-diagnostics -q ping` — `initialDelaySeconds: 30`, `periodSeconds: 30` |

Spring Boot Deployments: `resources.limits.memory: 768Mi` each; readiness/liveness on `/actuator/health`.

> **Why memory caps matter (uber-m3.md:1758):** the default MiniKube profile starts with ~6GB. The 5 PG StatefulSets alone consume 2.5GB; add the rest and you cross the cap, triggering pod evictions during grading.

> **Why probes matter (uber-m3.md:1760):** `kubectl wait --for=condition=ready` only signals true when readiness passes. Without per-DB readiness probes, the Spring Boot services start before their datasources are accepting connections.

## Deployment order (uber-m3.md:1729–1742, 1762–1768)

```bash
kubectl apply -f k8s/namespaces/
kubectl apply -f k8s/secrets/
kubectl apply -f k8s/pvcs/
kubectl apply -f k8s/statefulsets/        # all databases first
kubectl apply -f k8s/configmaps/

# Wait for every database, not just ride-postgres:
for db in user-postgres driver-postgres ride-postgres location-postgres payment-postgres mongodb redis elasticsearch neo4j cassandra rabbitmq; do
  kubectl wait --for=condition=ready pod -l app=$db -n uber --timeout=180s
done

kubectl apply -f k8s/deployments/         # services after databases
kubectl apply -f k8s/services/
kubectl apply -f k8s/api-gateway/
kubectl apply -f k8s/monitoring/
```

## NodePort table

| Resource | NodePort | Internal port |
|---|---|---|
| api-gateway | **30080** | 8080 |
| Grafana | **30030** | 3000 (uber-m3.md:2361) |

Everything else is ClusterIP-only.

## Slice ownership (uber-m3.md:2546–2562)

Shared infra is split across the 5 INFRA slices so no single member owns "all of K8s":

| Slice | Shared infra owned |
|---|---|
| **S1-INFRA** | api-gateway Maven module + JwtGatewayFilter + gateway K8s Deployment + NodePort Service (30080) + Mongo K8s StatefulSet + Service |
| **S2-INFRA** | `monitoring` namespace YAML + Loki K8s (StatefulSet + ConfigMap + PVC + Service named `loki`) + Redis K8s StatefulSet + Service |
| **S3-INFRA** | Prometheus K8s (Deployment + ConfigMap with the 5-job `prometheus.yml` + PVC + Service named `prometheus`) + Neo4j K8s StatefulSet + Service |
| **S4-INFRA** | Grafana K8s (Deployment + datasources ConfigMap pointing at Loki + Prometheus + dashboards ConfigMap embedding all 5 service dashboards + PVC + NodePort Service on 30030) + Cassandra K8s StatefulSet + Service |
| **S5-INFRA** | RabbitMQ K8s (StatefulSet + Service exposing 5672 + 15672) + Elasticsearch K8s StatefulSet + Service + saga end-to-end test scenarios A/B/C from §8.6 (JUnit integration tests) |

## Per-service slice deliverables

Each `S<n>-READ-DB` slice owns:
- Per-service PG StatefulSet + PVC + Secret + headless Service.
- `logback-spring.xml` (Loki4J appender).
- ≥3 LogQL panels.

Each `S<n>-EVENTS` slice owns:
- Spring Boot Deployment + ClusterIP Service + ConfigMap.
- Actuator config (`prometheus,health,info` exposure).
- ≥3 PromQL panels.

Each `S<n>-INFRA` slice owns:
- The service's gateway route entry in `api-gateway/application.yml`.
- The service's scrape job entry in `prometheus.yml`.
- The service's final dashboard JSON file.
- One assigned shared-infra item from the table above.
