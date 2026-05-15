# Payment Service — S5-INFRA Testing Guide

Deep test procedure for the **M3 deliverable #15 — S5-INFRA** slice (owner: Seif Tarek Mostafa, 55-24853). Covers everything this slice ships:

- RabbitMQ K8s StatefulSet + Service + PVC
- Elasticsearch K8s StatefulSet + Service + PVC
- payment-service entry in `api-gateway/application.yml`
- payment-service entry in `k8s/monitoring/prometheus/prometheus-configmap.yaml`
- `k8s/monitoring/grafana/dashboards/payment-dashboard.json` (3 LogQL + 3 PromQL panels)
- `payment-service/src/test/java/.../saga/SagaEndToEndIT.java` (§8.6 scenarios A/B/C)

All commands are written for **PowerShell 7+** on Windows. Prerequisites: Docker Desktop with Kubernetes enabled (or `minikube start --memory 6144 --cpus 4`), `kubectl` on PATH, `python` on PATH, `curl.exe` available.

---

## 0. Baseline — Maven + saga JUnit (sanity)

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

## 1. K8s manifests — dry-run validation

Catches typos and bad fields without spinning up the cluster:

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

## 2. RabbitMQ — deploy + functional test

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

## 3. Elasticsearch — deploy + index a document

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

## 4. Prometheus scrape config — validate with standalone Prometheus

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

## 5. Grafana dashboard — load the JSON, verify panels render

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

Invoke-RestMethod -Uri "http://admin:admin@localhost:3000/api/dashboards/db" `
    -Method Post -ContentType 'application/json' -Body $payload
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

## 7. What's still untestable on this branch

The full §14.2 demo (`PUT /api/rides/10/complete` → events ripple through all 5 services → ride status = PAID) requires S3-EVENTS and S5-EVENTS merged. Partial simulation of transport only:

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

## Pass criteria summary

| Step | Pass when |
|---|---|
| 0. Saga JUnit | `Tests run: 6, Failures: 0` |
| 1. Manifest dry-run | every file → `created (dry run)` |
| 2. RabbitMQ in-cluster | pod Ready, `Limits: memory: 512Mi`, `ping` succeeds, publish→get round-trips JSON |
| 3. Elasticsearch in-cluster | pod Ready, cluster yellow, indexed doc returned by `_search` |
| 4. Prometheus config | standalone Prometheus parses config, 5 active targets |
| 5. Grafana dashboard | 6 panels render with valid LogQL/PromQL queries |

Hit those six and S5-INFRA is verified to the maximum extent possible without the other 14 slices.

---

## PowerShell gotchas worth knowing

- `curl` is aliased to `Invoke-WebRequest`. Always use `curl.exe` explicitly for real curl behavior.
- **`curl.exe -d '$json'` strips inner double-quotes** when PowerShell parses the argument — ES will reject the malformed body. Use `Invoke-RestMethod -Body $json` for any JSON request body. Reserve `curl.exe` for header-only / query-string requests.
- **`Out-File -Encoding utf8` writes a UTF-8 BOM in Windows PowerShell** — many strict JSON parsers (Grafana included) reject the BOM with `bad request data` or similar. Either use `-Encoding utf8NoBOM` (PS 7+), use `[System.IO.File]::WriteAllText($path, $content)`, or skip the temp file entirely with `Invoke-RestMethod -Body $obj`.
- Maven on PowerShell: arguments with a `.` after `-D` may be split (`-Dsurefire.failIfNoSpecifiedTests=false` becomes a phase named `.failIfNoSpecifiedTests=false`). Quote the whole flag: `"-Dsurefire.failIfNoSpecifiedTests=false"`.
- Backtick `` ` `` is the line-continuation character (NOT backslash).
- Single-quoted strings (`'...'`) don't expand variables. Use double quotes (`"..."`) or here-strings (`@"..."@`) for `$var` interpolation.
- Heredocs use `@'...'@` (literal) or `@"..."@` (expanding). The closing `'@` / `"@` **must be at column 0**.
- `Start-Process` doesn't block — use it for `kubectl port-forward` backgrounding (`&` at end of line is bash, not PowerShell).
- Wrap multi-token pipelines in `(...)` to keep `Select-Object -Last 1` on the same logical statement when split across lines.
- Minikube K8s context: if `kubectl` fails with "connection refused" after a Minikube restart, the API server port has changed — run `minikube update-context` (or `minikube stop; minikube start` if that fails) to re-sync kubeconfig.
