<!-- Loaded by: skills/observability-bootstrap, skills/m3-orchestrator, skills/pr-check -->

# M3 Observability — Loki4J + Prometheus + Grafana

Source: `docs/m3/uber-m3.md` §11 (lines 1787–1928).

## Loki4J appender (uber-m3.md:1789–1851)

Add to each service's `pom.xml`:

```xml
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Per-service MDC fields (uber-m3.md:1801–1812)

`correlationId` is shared by all 5 services (set from the `X-Correlation-ID` header forwarded by api-gateway, or from the RabbitMQ message header in consumers). The remaining entity-specific keys differ:

| Service | Entity-specific MDC keys |
|---|---|
| user-service | `userId` |
| driver-service | `driverId`, `rideId`, `routingKey` |
| ride-service | `rideId`, `userId`, `driverId`, `paymentId`, `routingKey` |
| location-service | `driverId`, `rideId`, `routingKey` |
| payment-service | `paymentId`, `rideId`, `userId`, `routingKey` |

### `logback-spring.xml` (ride-service template, uber-m3.md:1813–1851)

```xml
<configuration>
    <appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
        <http>
            <url>http://loki.monitoring.svc.cluster.local:3100/loki/api/v1/push</url>
        </http>
        <format>
            <label>
                <pattern>app=uber,service=${spring.application.name},level=%level,env=k8s</pattern>
            </label>
            <message>
                <pattern>
                    {
                      "timestamp": "%d{ISO8601}",
                      "level": "%level",
                      "service": "${spring.application.name}",
                      "thread": "%thread",
                      "logger": "%logger{36}",
                      "correlationId": "%X{correlationId:-}",
                      "userId": "%X{userId:-}",
                      "driverId": "%X{driverId:-}",
                      "rideId": "%X{rideId:-}",
                      "paymentId": "%X{paymentId:-}",
                      "routingKey": "%X{routingKey:-}",
                      "message": "%msg"
                    }
                </pattern>
            </message>
        </format>
    </appender>
    <root level="INFO">
        <appender-ref ref="LOKI"/>
    </root>
</configuration>
```

> The example above is **ride-service** (the busiest). Each other service uses the same XML structure but **drops the MDC fields it does not populate** from the `<message><pattern>` block.

### MDC population (uber-m3.md:1853–1857)

- **`correlationId`** — populated by a servlet filter (`OncePerRequestFilter`) that reads the `X-Correlation-ID` header set by api-gateway and calls `MDC.put("correlationId", value)`. The filter must clear MDC in `finally`. RabbitMQ consumers must also read the `correlationId` header from the inbound `Message` and call `MDC.put` at the start of the listener method.
- **Entity IDs** (`userId`, `driverId`, `rideId`, `paymentId`) — populated manually by service-layer methods using `MDC.put("rideId", id.toString())` immediately before performing the operation, paired with `MDC.remove(...)` in a `finally` block.
- **`routingKey`** — set by RabbitMQ publishers and consumers to the routing key being processed.

### Required log points (uber-m3.md:1859–1876)

Use SLF4J: `private static final Logger log = LoggerFactory.getLogger(<Class>.class);`.

| Log point | Level | Suggested message format |
|---|---|---|
| Controller method entry | INFO | `"Received {} {}"` (HTTP method, path) |
| Controller method exit | INFO | `"Returning {} for {} {}"` (status, method, path) |
| Feign call — before request | INFO | `"Calling {}.{} with args={}"` (client, method, args) |
| Feign call — after success | INFO | `"{}.{} returned successfully"` (client, method) |
| Feign call — exception caught | WARN | `"Feign call to {} failed: {}"` (service, exception message) |
| RabbitMQ — event published | INFO | `"Published {} for {}={}"` (routingKey, entityName, id) |
| RabbitMQ — event consumed (start) | INFO | `"Consuming {} for {}={}"` (routingKey, entityName, id) |
| RabbitMQ — event processed (success) | INFO | `"Processed {} for {}={}"` (routingKey, entityName, id) |
| RabbitMQ — consumer error | ERROR | `"Failed to process {}: {}"` (routingKey, exception message) → DLQ |
| Saga state transition (S3 only) | INFO | `"Ride {} transitioning {} → {}"` (rideId, oldStatus, newStatus) |
| DB write success | INFO | `"{} {} saved with status={}"` (entityName, id, status) |
| Slow operation (> threshold) | WARN | `"Slow {} took {}ms"` (operationName, elapsedMs) — wrap operations expected to be slow under load |

### Required actuator config in `application.yml` (uber-m3.md:1878–1890)

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

## Dashboard per service (uber-m3.md:1892–1928)

Each of the 5 services has its own Grafana dashboard. Each dashboard has at minimum **3 LogQL panels** and **3 PromQL panels** chosen from the lists below. **Five dashboard JSON files must be submitted (one per service).**

### LogQL panel options (≥ 3 per service)

A LogQL query is built up in three layers:
1. **Label** — `{app="uber", service="ride-service", level="ERROR"}`
2. **Line** — `|= "search-text"`, `!= "exclude"`, `| json`, `| line_format "{{...}}"`
3. **Aggregator** — `count_over_time(...[1m])`, `rate(...[5m])`, `sum by (service) (...)`

Available panels (uber-m3.md:1908–1924):

1. **Error rate panel** — Count of ERROR-level log lines per service per minute. *Spike detection.*
2. **Correlation ID trace panel** — Filter all log lines by a specific `X-Correlation-ID` across all services. *Trace a single ride completion request.*
3. **RabbitMQ event audit panel** — Lines emitted by event publishers and consumers, filtered by routing key. *Show how many `ride.completed` events were published vs. how many `payment.initiated` events were consumed in the last hour.*
4. **Feign call outcomes panel** — Log lines for successful Feign responses vs. `FeignException` catches. *Detect when driver-service is degraded.*
5. **Saga state transitions panel** — Log lines at each saga step filtered by rideId. *Visualize the complete saga flow for ride ID=42: COMPLETED → PAYMENT_PENDING → PAID.*
6. **Slow operation warnings panel** — Log lines where elapsed time exceeded a threshold. *Alert when S5-F10 vehicle-type revenue aggregation takes > 5 seconds.*

### PromQL panel options (≥ 3 per service)

A PromQL query is built up in four layers:
1. **Metric** — e.g., `http_server_requests_seconds_count`, `jvm_memory_used_bytes`. Exposed by `/actuator/prometheus`, scraped every 15s.
2. **Label** — e.g., `{service="ride-service", uri="/api/rides", method="GET"}`.
3. **Range** — `[5m]`, `[1h]`.
4. **Function** — `rate(...)`, `increase(...)`, `histogram_quantile(0.99, ...)`, `sum by (uri) (...)`, `topk(5, ...)`.

Available panels (uber-m3.md:1939+):

1. **HTTP request rate panel** — Requests per second per endpoint.
2. **HTTP latency percentiles panel** — P50/P95/P99 latency per endpoint.
3. **JVM health panel** — Heap usage, GC pause duration, thread count.
4. **DB connection pool saturation** — HikariCP active vs. max.
5. **RabbitMQ message rate** — Publish/consume rates per routing key.
6. **Feign call outcomes** — Success/failure rates per downstream service.
7. **Endpoint top-N** — `topk(5, sum by (uri) (rate(http_server_requests_seconds_count[5m])))`.

## Monitoring stack (uber-m3.md:2340–2363)

| Component | Image / version | Where it lives | Endpoint |
|---|---|---|---|
| Loki | `loki:2.9.4` | `k8s/monitoring/loki/` (ConfigMap + PVC + StatefulSet + Service named `loki`) | `http://loki.monitoring.svc.cluster.local:3100/loki/api/v1/push` |
| Prometheus | `prom/prometheus:v2.51.2` | `k8s/monitoring/prometheus/` (ConfigMap with 5-job scrape config + PVC + Deployment + Service named `prometheus`) | `http://prometheus.monitoring.svc.cluster.local:9090` |
| Grafana | `grafana/grafana:10.4.2` | `k8s/monitoring/grafana/` (datasources ConfigMap + dashboards ConfigMap embedding all 5 service dashboards + PVC + NodePort Service on 30030) | `http://$(minikube ip):30030` |

## Slice ownership

Per uber-m3.md:2553, 2556, 2559:

- **S2-INFRA** owns Loki K8s.
- **S3-INFRA** owns Prometheus K8s + its 5-job scrape config.
- **S4-INFRA** owns Grafana K8s + the dashboards ConfigMap that aggregates all 5 service dashboards.

Each per-service `INFRA` slice contributes:
- One scrape job entry in `prometheus.yml`.
- One service dashboard JSON file (≥3 LogQL panels + ≥3 PromQL panels).
- One gateway route entry in `api-gateway/application.yml`.
