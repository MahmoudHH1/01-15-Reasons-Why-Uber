# S5-INFRA — Test Walkthrough

End-to-end verification of **M3 deliverable #15 (S5-INFRA)** per `docs/m3/uber-m3.md` §13.2 line 15 (owner: Seif Tarek Mostafa, 55-24853). Every step below maps directly to a spec clause and demonstrates the deliverable is real on a live Minikube cluster — not just a YAML in the repo.

## What this slice ships (per §13.2 row 15 + §10.8 + §11.2 + §8.6)

| Artifact in PR | Spec mandate | This walkthrough |
|---|---|---|
| `k8s/statefulsets/rabbitmq-statefulset.yaml` + `services/rabbitmq-svc.yaml` (5672 + 15672) + `pvcs/rabbitmq-pvc.yaml` | §10.8 `resources.limits.memory: 512Mi` + `rabbitmq-diagnostics -q ping` probe (initialDelaySeconds=30, periodSeconds=30); §13.2 row 15 "RabbitMQ K8s StatefulSet + Service exposing 5672 + 15672" | Step 2 |
| `k8s/statefulsets/elasticsearch-statefulset.yaml` + `services/elasticsearch-svc.yaml` + `pvcs/elasticsearch-pvc.yaml` | §10.8 `resources.limits.memory: 768Mi` + `httpGet /_cluster/health?wait_for_status=yellow` probe (initialDelaySeconds=60); §13.2 row 15 "Elasticsearch K8s StatefulSet + Service" | Step 3 |
| payment-service route block in `api-gateway/src/main/resources/application.yml` | §9.2 reactive routing (`spring.cloud.gateway.server.webflux.routes`) + `Path=/api/payments/**` predicate | Step 1 (YAML parse) |
| payment-service scrape job in `k8s/monitoring/prometheus/prometheus-configmap.yaml` | §11.5 5-job scrape config at 15s interval against `<svc>.uber.svc.cluster.local:8080/actuator/prometheus` | Step 4 |
| `k8s/monitoring/grafana/dashboards/payment-dashboard.json` | §11.2 ≥3 LogQL + ≥3 PromQL panels per service (we ship 3 + 3 = 6) | Step 1 (JSON parse) + Step 5 (render) |
| `payment-service/src/test/java/.../saga/SagaEndToEndIT.java` | §13.2 row 15 "saga end-to-end test scenarios A/B/C from §8.6 implemented as JUnit integration tests"; §8.6 scenarios A (happy path), B (5-hop compensation cascade via S5-F12 Strategy), C (pre-check failure aborts before publish) | Step 0 |

**Final integration role (§13.4 line 3 / §13.2 §5 "Deploy-time independence"):** once all 15 slices are merged, the S5-INFRA owner re-runs the §8.6 scenarios on the live cluster and signs off. This walkthrough is the slice-isolated proof; the live-cluster run waits for Wave 4.

All commands are written for **PowerShell 7+** on Windows. Prerequisites: Docker Desktop with Kubernetes enabled (or `minikube start --memory 6144 --cpus 4`), `kubectl` on PATH, `python` on PATH, `curl.exe` available.

---

## 0. Baseline — Maven build + saga JUnit (§8.6 + §13.2 row 15)

Proves the §13.2-mandated saga JUnit tests exist, compile, and pass. Each test maps to §8.6:
- **Scenario A** — happy path: PENDING → COMPLETED, `payment.completed` payload carries `paymentId / rideId / amount` (§8.6 step 7 + §2.8 record `PaymentCompletedEvent`).
- **Scenario B** — payment failure compensation: drives the real S5-F12 `RefundStrategySelector` and all three concrete strategies (`FullRefundWithSurgeStrategy`, `BaseFareOnlyRefundStrategy`, `NoRefundStrategy`/`DeniedRefundResult`). This is the **only** place §13.2 row 15 mandates direct exercise of S5-F12 from the saga path.
- **Scenario C** — pre-check failure: no PENDING payment created when `location-service` returns 404 stale (§8.6 step 4 "no `ride.completed` event"); ride.cancelled with no pre-existing payment is a state-based-idempotency no-op (§16 critical rule 11).

```powershell
payment-service\mvnw.cmd clean install -DskipTests
cd payment-service
.\mvnw.cmd test -Dtest=SagaEndToEndIT
cd ..
```

**Pass:** `BUILD SUCCESS`, `Tests run: 6, Failures: 0`.

> Run the test command from inside `payment-service/` (no `-pl ... -am`) so Maven doesn't visit `contracts/` — Surefire 3.2.5 fails the build by default when `-Dtest=Foo` matches zero tests in *any* visited module. `contracts:1.0-SNAPSHOT` is already in your local `~/.m2/` from the first `mvn install`, so Maven doesn't need to rebuild it.
>
> If you must run from the repo root, quote the surefire flag so PowerShell doesn't split it at the dot:
> `payment-service\mvnw.cmd -pl payment-service -am test -Dtest=SagaEndToEndIT "-Dsurefire.failIfNoSpecifiedTests=false"`

Inspect that the saga test exercises the real S5-F12 strategy classes:

```powershell
Select-String -Pattern "FullRefundWithSurgeStrategy|BaseFareOnlyRefundStrategy|NoRefundStrategy" `
  -Path payment-service\src\test\java\com\team01\uber\payment\saga\SagaEndToEndIT.java
```

Expect hits inside Scenario B's three cases.

---

## 1. K8s manifests — dry-run validation (§10.1 directory structure + §10.8 specs)

Confirms every manifest this slice ships parses against the live Kubernetes API schema. Covers the `k8s/{pvcs,statefulsets,services,monitoring}/` paths from §10.1.

```powershell
$manifests = @(
    "k8s/pvcs/rabbitmq-pvc.yaml",
    "k8s/statefulsets/rabbitmq-statefulset.yaml",
    "k8s/services/rabbitmq-svc.yaml",
    "k8s/pvcs/elasticsearch-pvc.yaml",
    "k8s/statefulsets/elasticsearch-statefulset.yaml",
    "k8s/services/elasticsearch-svc.yaml",
    "k8s/monitoring/prometheus/prometheus-configmap.yaml",
    "k8s/monitoring/grafana/grafana-dashboards.yaml"
)
foreach ($f in $manifests) {
    $ns = if ($f -like '*monitoring*') { 'monitoring' } else { 'uber' }
    "{0,-55} -n {1,-10} " -f $f, $ns | Write-Host -NoNewline
    (kubectl apply --dry-run=client -f $f --namespace=$ns 2>&1) | Select-Object -Last 1
}
```

**Pass:** every line ends with `created (dry run)` or `configured (dry run)`.

Validate the dashboard JSON + gateway YAML:

```powershell
python -c "import json; d=json.load(open('k8s/monitoring/grafana/dashboards/payment-dashboard.json')); print('panels:', len(d['panels']))"
# Expect: panels: 6

python -c "import yaml; y=yaml.safe_load(open('api-gateway/src/main/resources/application.yml')); print('ok')"
# Expect: ok   (pip install pyyaml first if missing)
```

---

## 2. RabbitMQ — deploy + functional test (§10.8 + §2.6 + §2.7 + §2.9)

Proves the RabbitMQ infra owned by S5-INFRA actually moves messages. §10.8 mandates `resources.limits.memory: 512Mi` and a `rabbitmq-diagnostics -q ping` exec probe. §2.6 mandates the connection config (`spring.rabbitmq.host=rabbitmq`, port 5672, guest/guest, `acknowledge-mode: auto`, `default-requeue-rejected: false`, `max-attempts: 3`). The publish/consume round-trip uses `payment.events` (the §2.7 producer-owned TopicExchange) with routing key `payment.completed` (§2.9 row "payment-service / payment.events / payment.completed / PaymentCompletedEvent").

```powershell
kubectl create namespace uber
kubectl apply -f k8s/pvcs/rabbitmq-pvc.yaml -n uber
kubectl apply -f k8s/statefulsets/rabbitmq-statefulset.yaml -n uber
kubectl apply -f k8s/services/rabbitmq-svc.yaml -n uber

kubectl wait --for=condition=ready pod -l app=rabbitmq -n uber --timeout=180s
kubectl get pod -n uber -l app=rabbitmq
```

**Pass:** `rabbitmq-0  1/1  Running`.

### Verify §10.8 memory cap

```powershell
kubectl describe pod rabbitmq-0 -n uber | Select-String -Pattern "Limits:|Requests:|memory" -Context 0,2
```

**Pass:** `Limits: memory: 512Mi` and `Requests: memory: 512Mi` (auto-defaulted → QoS class Guaranteed).

Optionally read the cgroup limit directly (avoids needing metrics-server):

```powershell
kubectl exec -n uber rabbitmq-0 -- cat /sys/fs/cgroup/memory.max
# Expect: 536870912   (= 512 * 1024 * 1024)
```

### Health probe matches

```powershell
kubectl exec -n uber rabbitmq-0 -- rabbitmq-diagnostics -q ping
# Expect: "Ping succeeded"
```

### Real message flow — publish + consume

This is what proves RabbitMQ moves messages for the saga.

```powershell
kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest declare exchange `
    name=payment.events type=topic durable=true

kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest declare queue `
    name=payment.saga-listener durable=true

kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest declare binding `
    source=payment.events destination=payment.saga-listener routing_key='payment.#'

kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest publish `
    exchange=payment.events routing_key=payment.completed `
    payload='{\"paymentId\":100,\"rideId\":10,\"amount\":200.0}'

kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest get `
    queue=payment.saga-listener count=1
```

**Pass:** the final `get` returns a table with the JSON payload in the `payload` column.

### Browse the management UI

```powershell
Start-Process -NoNewWindow kubectl -ArgumentList "port-forward","-n","uber","svc/rabbitmq","15672:15672"
Start-Sleep 3
Start-Process "http://localhost:15672"
# Login: guest / guest
# Confirm exchange "payment.events" + queue "payment.saga-listener" visible.

# Stop port-forward when done:
Get-Process kubectl -ErrorAction SilentlyContinue | Stop-Process
```

---

## 3. Elasticsearch — deploy + index a document (§10.8 + §1.3 driver index)

Proves the ES infra owned by S5-INFRA accepts index creation + writes + searches — exactly what S2-F10 (full-text driver search) and S2-F11 (driver auto-index on CRUD) will do once those slices land. §10.8 mandates image `elasticsearch:8.19.12`, `resources.limits.memory: 768Mi`, `ES_JAVA_OPTS=-Xms512m -Xmx512m`, and `httpGet /_cluster/health?wait_for_status=yellow` probe at `initialDelaySeconds: 60`. §1.3 mandates the `drivers` index lives in driver-service's namespace ownership.

```powershell
kubectl apply -f k8s/pvcs/elasticsearch-pvc.yaml -n uber
kubectl apply -f k8s/statefulsets/elasticsearch-statefulset.yaml -n uber
kubectl apply -f k8s/services/elasticsearch-svc.yaml -n uber

# ES takes ~3 min on Docker Desktop:
kubectl wait --for=condition=ready pod -l app=elasticsearch -n uber --timeout=300s
```

### If ES is OOMKilled (common on Minikube)

ES 8.19.12 loads 80+ modules at startup and can OOM under the spec's 768Mi cgroup cap when JVM heap = 512Mi (leaves only ~256Mi for native, JIT, off-heap). Symptom: pod stuck in `CrashLoopBackOff` with `OOMKilled` and exit code 137.

Fix without changing the YAML on disk — shrink the JVM heap so more memory is left for native:

```powershell
kubectl set env statefulset/elasticsearch -n uber ES_JAVA_OPTS="-Xms256m -Xmx256m"
kubectl delete pod elasticsearch-0 -n uber
kubectl wait --for=condition=ready pod -l app=elasticsearch -n uber --timeout=300s
```

The cluster runs with 256Mi heap until you destroy the StatefulSet. Don't commit this change — the spec mandates 512Mi heap + 768Mi limit; the heap shrink is a local-Minikube fudge.

### Cluster health

```powershell
$health = kubectl exec -n uber elasticsearch-0 -- curl -s localhost:9200/_cluster/health | ConvertFrom-Json
"$($health.status) $($health.number_of_nodes)"
# Expect: "green 1" (no indices yet) or "yellow 1" (after creating drivers index with default replica=1)
```

### Index a sample driver document (what S2-F11 will do)

Use `Invoke-RestMethod` — PowerShell-native, no JSON-quoting pitfalls. (Don't use `curl.exe` here: PowerShell strips the inner double-quotes before passing the argument, and ES rejects the unquoted JSON.)

```powershell
Start-Process -NoNewWindow kubectl -ArgumentList "port-forward","-n","uber","svc/elasticsearch","9200:9200"
Start-Sleep 3

# Clean slate in case a previous failed request auto-created an empty `drivers` index:
try { Invoke-RestMethod -Uri http://localhost:9200/drivers -Method Delete } catch {}

# Create the index with explicit mappings:
$mapping = @'
{"mappings":{"properties":{"id":{"type":"long"},"name":{"type":"text"},"status":{"type":"keyword"}}}}
'@
Invoke-RestMethod -Uri http://localhost:9200/drivers -Method Put -ContentType 'application/json' -Body $mapping
# Expect: acknowledged=True, shards_acknowledged=True, index=drivers

# Index the document:
$doc = '{"id":1,"name":"Mahmoud Hebishy","status":"AVAILABLE"}'
Invoke-RestMethod -Uri http://localhost:9200/drivers/_doc/1 -Method Post -ContentType 'application/json' -Body $doc
# Expect: result=created, _id=1, _version=1

# Force a refresh so the doc is searchable immediately (default refresh interval is 1s):
Invoke-RestMethod -Uri http://localhost:9200/drivers/_refresh -Method Post

# Full-text search (what S2-F10 /search/full-text will do):
Invoke-RestMethod -Uri 'http://localhost:9200/drivers/_search?q=name:mahmoud' | ConvertTo-Json -Depth 6

Get-Process kubectl -ErrorAction SilentlyContinue | Stop-Process
```

**Pass:** the search response shows `hits.total.value = 1` and `_source.name = "Mahmoud Hebishy"`.

---

## 4. Prometheus scrape config — validate with standalone Prometheus (§11.5)

Proves my payment-service entry in the shared `prometheus-configmap.yaml` is part of a well-formed §11.5 scrape config. The spec mandates 5 jobs, one per service, each at 15s interval against `<svc>.uber.svc.cluster.local:8080/actuator/prometheus`. We boot a standalone Prometheus pointed at the ConfigMap's `prometheus.yml` content and confirm all 5 jobs register.

```powershell
python -c @'
import yaml
cm = yaml.safe_load(open('k8s/monitoring/prometheus/prometheus-configmap.yaml'))
open('prometheus-test.yml','w').write(cm['data']['prometheus.yml'])
'@

docker run --rm -d --name prom-test -p 9090:9090 `
    -v "${PWD}/prometheus-test.yml:/etc/prometheus/prometheus.yml" `
    prom/prometheus:v2.51.2

Start-Sleep 6

$targets = curl.exe -s http://localhost:9090/api/v1/targets | ConvertFrom-Json
$targets.data.activeTargets | ForEach-Object { $_.labels.job } | Sort-Object -Unique
# Expect 5 lines: driver-service, location-service, payment-service, ride-service, user-service

docker stop prom-test
Remove-Item prometheus-test.yml
```

**Pass:** all 5 jobs registered. Targets will show `DOWN` (no services running) — that's fine; the point is Prometheus parsed the config.

---

## 5. Grafana dashboard — load the JSON, verify panels render (§11.2 + §11.3 + §11.4)

Proves `payment-dashboard.json` is a valid Grafana dashboard. §11.2 mandates **≥3 LogQL + ≥3 PromQL panels per service**; we ship 6 panels split 3+3 from the §11.3 / §11.4 options. The standalone Grafana proves the JSON loads, the panels render in the correct layout, and each panel's query filters on `service="payment-service"`.

```powershell
docker run --rm -d --name graf-test -p 3000:3000 `
    -v "${PWD}/k8s/monitoring/grafana/dashboards:/var/lib/grafana/dashboards:ro" `
    grafana/grafana:10.4.2

Start-Sleep 10

# Health:
Invoke-RestMethod -Uri http://localhost:3000/api/health
# Expect: database=ok, version=10.4.x

# Build import body in-memory (no temp file = no UTF-8 BOM):
$dashboard = Get-Content k8s/monitoring/grafana/dashboards/payment-dashboard.json -Raw | ConvertFrom-Json
$payload = @{ dashboard = $dashboard; overwrite = $true } | ConvertTo-Json -Depth 20

# Send Basic auth as an explicit header — Invoke-RestMethod doesn't reliably honor
# the `http://user:pass@host` URL form (returns 401):
$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("admin:admin"))
$headers = @{ Authorization = "Basic $auth" }

Invoke-RestMethod -Uri "http://localhost:3000/api/dashboards/db" `
    -Method Post -ContentType 'application/json' -Body $payload -Headers $headers
# Expect: status=success, uid=payment-service, url=/d/payment-service/...

# > Don't use `Out-File -Encoding utf8 | curl.exe --data @file.json` here — Windows PowerShell
# > prepends a UTF-8 BOM, and Grafana's JSON parser rejects it with the unhelpful error
# > `bad request data`. The `ConvertFrom-Json | ConvertTo-Json` round-trip + `Invoke-RestMethod`
# > sends pure UTF-8 directly.

Start-Process "http://localhost:3000/d/payment-service/payment-service"
# Login: admin / admin (skip the password change prompt with "Skip")

docker stop graf-test
```

**Pass:** all 6 panels render. Each says "No data" (no datasources wired) — that's expected. Edit each panel and confirm the LogQL/PromQL queries reference `service="payment-service"`.

---

## 6. Cleanup

```powershell
kubectl delete namespace uber
docker ps --filter "name=prom-test" --filter "name=graf-test" -q | ForEach-Object { docker stop $_ }
```

---

## 7. What's still untestable on this branch (§14.2 + §13.4 Final integration)

The full §14.2 demo (`PUT /api/rides/10/complete` via gateway NodePort 30080 → events ripple through all 5 services → ride status = PAID) requires S3-EVENTS and S5-EVENTS merged. Per §13.4: *"Once all 15 slices are merged, S5-INFRA owner runs the saga end-to-end test scenarios A/B/C (§8.6) and signs off."* That live-cluster run is the deliverable's final acceptance — not done on this branch. Partial simulation of transport only:

```powershell
# After step 2, also bind payment.saga-listener to ride.events.ride.completed:
kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest declare exchange `
    name=ride.events type=topic durable=true
kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest declare binding `
    source=ride.events destination=payment.saga-listener routing_key='ride.completed'

# Publish a fake ride.completed:
kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest publish `
    exchange=ride.events routing_key=ride.completed `
    payload='{\"rideId\":10,\"userId\":1,\"driverId\":5,\"fare\":200.0}'

# Confirm it lands in payment-service's saga queue:
kubectl exec -n uber rabbitmq-0 -- rabbitmqadmin -u guest -p guest get `
    queue=payment.saga-listener count=1
```

That proves the **transport** works. The **business logic** in payment-service (consuming `ride.completed` and creating a PENDING payment) can't run until S5-EVENTS lands its `@RabbitListener`.

---

## Pass criteria summary (mapped to spec)

| Step | Pass when | Spec clause |
|---|---|---|
| 0. Saga JUnit | `Tests run: 6, Failures: 0`; all 3 S5-F12 strategy classes exercised in Scenario B | §8.6 A/B/C; §13.2 row 15 |
| 1. Manifest dry-run | every file → `created (dry run)`; dashboard JSON has 6 panels; gateway YAML parses | §10.1 layout; §11.2 ≥3+3 panels |
| 2. RabbitMQ in-cluster | pod Ready; `Limits: memory: 512Mi`; `rabbitmq-diagnostics ping` succeeds; publish→get round-trips JSON via `payment.events` exchange + `payment.#` binding | §10.8; §2.7; §2.9 |
| 3. Elasticsearch in-cluster | pod Ready; cluster green/yellow on `_cluster/health`; PUT + POST + refresh + search round-trips a document | §10.8; §1.3 driver index |
| 4. Prometheus config | standalone Prometheus parses ConfigMap content; 5 active targets register | §11.5 scrape config |
| 5. Grafana dashboard | 6 panels (3 LogQL + 3 PromQL) render in correct layout; each query filters `service="payment-service"` | §11.2; §11.3; §11.4 |

Hit those six and S5-INFRA is verified to the maximum extent possible without the other 14 slices. Final acceptance is the §13.4 Wave-4 live-cluster run.

---

## PowerShell gotchas worth knowing

- `curl` is aliased to `Invoke-WebRequest`. Always use `curl.exe` explicitly for real curl behavior.
- **`curl.exe -d '$json'` strips inner double-quotes** when PowerShell parses the argument — ES will reject the malformed body. Use `Invoke-RestMethod -Body $json` for any JSON request body. Reserve `curl.exe` for header-only / query-string requests.
- **`Out-File -Encoding utf8` writes a UTF-8 BOM in Windows PowerShell** — many strict JSON parsers (Grafana included) reject the BOM with `bad request data` or similar. Either use `-Encoding utf8NoBOM` (PS 7+), use `[System.IO.File]::WriteAllText($path, $content)`, or skip the temp file entirely with `Invoke-RestMethod -Body $obj`.
- **`Invoke-RestMethod` doesn't reliably honor `http://user:pass@host` URLs** — they often return 401. Send Basic auth as an explicit `Authorization` header: `$h = @{Authorization = "Basic $([Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('user:pass')))"}`.
- Maven on PowerShell: arguments with a `.` after `-D` may be split (`-Dsurefire.failIfNoSpecifiedTests=false` becomes a phase named `.failIfNoSpecifiedTests=false`). Quote the whole flag: `"-Dsurefire.failIfNoSpecifiedTests=false"`.
- Backtick `` ` `` is the line-continuation character (NOT backslash).
- Single-quoted strings (`'...'`) don't expand variables. Use double quotes (`"..."`) or here-strings (`@"..."@`) for `$var` interpolation.
- Heredocs use `@'...'@` (literal) or `@"..."@` (expanding). The closing `'@` / `"@` **must be at column 0**.
- `Start-Process` doesn't block — use it for `kubectl port-forward` backgrounding (`&` at end of line is bash, not PowerShell).
- Wrap multi-token pipelines in `(...)` to keep `Select-Object -Last 1` on the same logical statement when split across lines.
- Minikube K8s context: if `kubectl` fails with "connection refused" after a Minikube restart, the API server port has changed — run `minikube update-context` (or `minikube stop; minikube start` if that fails) to re-sync kubeconfig.
