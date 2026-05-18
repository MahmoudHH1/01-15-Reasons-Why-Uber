****# M2 End-to-End Test Suite

Black-box, curl-based tests for the Uber-replica M2 milestone. Every
endpoint in every service is exercised across all variant scenarios
described in `Uber_descriptionM2.pdf` (§3 design patterns, §4 M1
retrofits, §5 JWT, §6 docker stack, §7 NoSQL entities, §8 caching,
§9 cross-cutting requirements, §10 features).

The suite is **read-only on the codebase** — it only hits HTTP, Redis,
MongoDB, Elasticsearch, and Cassandra. It does not mutate source files.
MongoDB is a **HARD dependency** — its unavailability will prevent
service startup and cause event-logged features to fail immediately.

---

## Quick start (TL;DR)

```bash
# 1. Bring the stack up
docker compose up -d --build

# 2. Wait until every container is healthy
docker compose ps

# 3. Run the suite
./tests/run-all.sh
```

Final line is `GRAND TOTAL:  <PASS> PASS / <FAIL> FAIL / <SKIP> SKIP`.
Exit code = number of FAILs (0 = green).

---

## What is covered

| Script | Spec sections | What it asserts |
|---|---|---|
| `00-health.sh`              | §5.4, §9.1            | 5 health endpoints public, return 200 "OK" |
| `01-cc-jwt.sh`              | §5, §9.1, §9.2, §3.4, §3.6 | Public set is exactly {register, login, health}; missing/malformed/bad-signature/expired tokens 401; same JWT works on every service (DP-5 Singleton + shared secret); CC-2 role mgmt full matrix; DP-3 UserLoaderHandler — token for deleted user → 401 |
| `02-cc-cache.sh`            | §4.4, §8              | List-not-cached, GET-by-id cached, PUT invalidates entity-detail key, wildcard invalidation on feature caches, soft-dep PG fallback, TTL ≤ 16 min on entity detail |
| `03-cc-design-patterns.sh`  | §3.1–3.8, §4.5        | DP-2/6 Observer+Factory writes auth_events / driver_events; DP-4 Builder shape on S2-F12 dashboard; §3.3.f S1-F2 preferences observer retrofit; DP-1 Strategy distinct path on S5-F12 + M1 PUT refund coexists |
| `04-cc-docker-yaml.sh`      | §6, §9.5, §9.6        | docker-compose has 6 DBs with pinned tags + memory caps; per-service `application.yml` exists; no leftover `application.properties` |
| `10-user-service.sh`        | §10.1, §4.1, §4.2, §9.2 | S1-F10/F11/F12 full matrices, CC-2 role mgmt, BCrypt (no plaintext leak), JWT `role` claim verification (§4.2.c/d), LOGGED_IN audit, ADMIN-bypass on activity feed, all M1 S1-F1..S1-F9, CRUD User + SavedAddress |
| `20-driver-service.sh`      | §10.2, §7.2, §4.5     | S2-F10 full-text search (filters + auto-index on PUT/DELETE), S2-F11 explicit + auto-deindex, S2-F12 dashboard with **exact math** (5 rides × 100/200/150/300/250 → totalEarnings=1000), zero-state driver (§10.2.3.c), missing-description JSONB (§10.2.2.b), all M1 S2-F1..S2-F9, CRUD Driver + DriverDocument |
| `30-ride-service.sh`        | §10.3, §7.3, §4.4.4   | S3-F10 analytics with **exact math** (10 rides 6/2/2 → completionRate=0.6, ridesByStatus.COMPLETED=6), empty-range zeros, S3-F11 idempotent record-interaction (Neo4j marker), S3-F12 graph-seeded recs (A→D1,D2 / B→D1,D3 / C→D2,D4 → recs include D3,D4), empty-recs path, all M1 S3-F1..S3-F9, CRUD Ride + RideStop |
| `40-location-service.sh`    | §10.4, §7.4, §4.4.4   | S4-F10 analytics with **exact math** (8 events / 3 drivers / hours 8&17), S4-F11 GPS write (Cassandra primary + Mongo audit), S4-F12 timeline narrow-time-range filter (§10.4.3.b), wildcard invalidation S4-F11→S4-F12::{driverId} and S4-F10::*, all M1 S4-F1..S4-F9, CRUD Location |
| `50-payment-service.sh`     | §10.5, §3.2, §4.5, §4.6 | S5-F4 retrofit (CREATED+COMPLETED audits + surgeFee), `?simulateFailure=true` → FAILED, S5-F10 vehicle-type **exact math** (SEDAN=600, SUV=400), S5-F11 method **exact math** (CC 5s/2f/total=500, CASH 3s/total=300), S5-F12 all 3 strategies (Full/BaseFareOnly/NoRefund) with exact numeric refundAmount, REFUND_DENIED audit, denial-path cache invalidation, M1 PUT /refund REFUNDED audit (§4.5.f), CRUD Payment + Coupon + PaymentCoupon |

Every assertion is annotated with its spec citation (`§<chapter.section.step>`)
so any FAIL traces straight back to the PDF clause.

**Total assertions: ~322** (224 PASS / 79 FAIL / 19 SKIP on a stack with
the known M2 implementation gaps).

---

## Prerequisites

### 1. Tooling

The suite is bash-based and uses these CLIs. Install whichever set matches
your OS.

| Tool        | Why                                          | Installation |
|-------------|----------------------------------------------|---|
| `bash`      | Test runner                                  | Pre-installed on macOS/Linux. Windows: Git Bash (ships with Git for Windows) **or** WSL2 |
| `curl`      | HTTP client                                  | macOS/Linux: pre-installed. Windows: bundled with Git for Windows; or `winget install curl.curl` |
| `jq`        | JSON parsing in assertions                   | macOS: `brew install jq`. Linux: `sudo apt install jq` / `sudo dnf install jq`. Windows: `winget install jqlang.jq` then restart your shell. |
| `docker`    | The 6-DB stack + (fallback) running redis-cli/mongosh inside containers | Docker Desktop (Windows/macOS) or `apt install docker.io docker-compose-plugin` (Linux). Make sure Docker Desktop is **running** before you start the suite. |
| `redis-cli` (optional) | Faster Redis assertions on the host    | macOS: `brew install redis`. Linux: `sudo apt install redis-tools`. Windows: optional — the suite auto-falls-back to `docker exec uber-redis redis-cli ...` |
| `mongosh` (optional)   | Faster Mongo assertions on the host    | https://www.mongodb.com/try/download/shell — same auto-fallback to `docker exec uber-mongo mongosh ...` |

Verify everything is on PATH:

```bash
for t in bash curl jq docker; do which "$t" || echo "MISSING: $t"; done
```

### 2. Running on Windows specifically

You have three usable shells. The suite was developed primarily on
**Git Bash**, and that's the shortest path on Windows:

#### Option A — Git Bash (recommended on Windows)

1. Install Git for Windows (https://git-scm.com/download/win) — this gives
   you `bash`, `curl`, and a working POSIX environment.
2. Install jq:
   ```powershell
   winget install jqlang.jq
   ```
   Open a **new** Git Bash window so the updated PATH takes effect, then
   confirm:
   ```bash
   jq --version    # should print jq-1.x.x
   ```
   If `jq --version` fails, locate the binary and symlink it:
   ```bash
   JQ_DIR="/c/Users/$USERNAME/AppData/Local/Microsoft/WinGet/Packages/jqlang.jq_Microsoft.Winget.Source_8wekyb3d8bbwe"
   ln -sf "$JQ_DIR/jq.exe" /usr/bin/jq
   ```
3. Make sure Docker Desktop is running (`docker version` should report
   both Client and Server).
4. From the repo root in Git Bash:
   ```bash
   docker compose up -d --build
   ./tests/run-all.sh
   ```

#### Option B — WSL2 (Ubuntu)

If you prefer a real Linux environment:

```bash
sudo apt update
sudo apt install -y bash curl jq
# Docker Desktop's WSL integration auto-exposes the docker CLI.
# If you don't use Docker Desktop, install docker-ce inside WSL.
docker compose up -d --build
./tests/run-all.sh
```

The repo on `E:\` is reachable from WSL at `/mnt/e/Semester 10/ACL/uber`.

#### Option C — PowerShell (NOT recommended)

The scripts use bash here-documents and POSIX flow control. PowerShell
won't run them directly. Use Git Bash or WSL.

### 3. Stack

```bash
docker compose up -d --build         # ~3-5 minutes on first run
docker compose ps                    # confirm everyone is "(healthy)"
```

Expected `docker compose ps` row count: **12** containers (5 services +
6 databases + pgAdmin).

Verify health endpoints respond before running the suite:

```bash
for p in 8081 8082 8083 8084 8085; do
  case $p in
    8081) e=users    ;;
    8082) e=drivers  ;;
    8083) e=rides    ;;
    8084) e=locations;;
    8085) e=payments ;;
  esac
  printf "%s -> " "$p:$e"
  curl -sS -m 5 -o /dev/null -w "%{http_code}\n" "http://localhost:$p/api/$e/health"
done
```

All five must return `200`. If any returns `000` ("Empty reply") or
`502`, see **Troubleshooting** below.

### 4. Optional ADMIN seed

CC-2 (`PUT /api/users/{id}/role`) requires a seeded ADMIN user.

**Good news: nothing to do.** `user-service/src/main/java/com/team01/uber/user/config/DataSeeder.java`
auto-creates the admin on every boot if it doesn't already exist:

| Field    | Value             |
|----------|-------------------|
| email    | `admin@uber.com`  |
| password | `admin123`        |
| role     | `ADMIN`           |
| status   | `ACTIVE`          |

The suite's defaults match these exactly — no env vars needed. Confirm it works:

```bash
curl -sS -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@uber.com","password":"admin123"}' | jq .token
```

Should print a JWT, not `null`. If the seeder didn't run (you cleared the
DB volume mid-run), restart user-service: `docker compose restart user-service`.

**Override only if your seed differs**:

```bash
ADMIN_EMAIL=admin@example.com \
ADMIN_PASSWORD=changed-it \
./tests/run-all.sh
```

**Or insert one manually** (skip the seeder):

```sql
INSERT INTO users (name, email, phone, password, role, status, created_at)
VALUES (
  'Admin',
  'admin@uber.com',
  '+201000000000',
  -- BCrypt of "admin123"
  '$2a$10$qP1Qjh0vLqs8FzGhhsNpKuSnjGxnD2dV7lFdAo1mQ3o4hyT9sZEgC',
  'ADMIN',
  'ACTIVE',
  NOW()
);
```

If no admin is reachable, the CC-2 sub-tests are SKIPPED rather than
FAILED — the rest of the suite still runs.

---

## Running the suite

From the repo root:

```bash
# Everything
./tests/run-all.sh

# Just the cross-cutting (CC) requirements
./tests/run-all.sh cc

# Just the per-service scripts (any keyword that appears in the filename)
./tests/run-all.sh user driver ride location payment

# A single script
./tests/10-user-service.sh
```

CI integration:

```bash
./tests/run-all.sh && echo "M2 green" || echo "M2 red ($? FAILs)"
```

---

## Configuration via env vars

Every URL, credential, and host is overridable. Defaults match
`docker-compose.yaml`. Common overrides:

```bash
# Hit a remote stack instead of localhost
USER_URL=http://staging:8081 \
DRIVER_URL=http://staging:8082 \
RIDE_URL=http://staging:8083 \
LOCATION_URL=http://staging:8084 \
PAYMENT_URL=http://staging:8085 \
ADMIN_EMAIL=admin@example.com \
ADMIN_PASSWORD=...                  \
REDIS_PASSWORD=redispass \
./tests/run-all.sh
```

Full list (with defaults) in `tests/lib/common.sh`:

| Var | Default | Purpose |
|---|---|---|
| `USER_URL`     | `http://localhost:8081` | user-service base URL |
| `DRIVER_URL`   | `http://localhost:8082` | driver-service base URL |
| `RIDE_URL`     | `http://localhost:8083` | ride-service base URL |
| `LOCATION_URL` | `http://localhost:8084` | location-service base URL |
| `PAYMENT_URL`  | `http://localhost:8085` | payment-service base URL |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / `redispass` | Used when `redis-cli` is on the host |
| `MONGO_HOST` / `MONGO_PORT` / `MONGO_USER` / `MONGO_PASSWORD` / `MONGO_DB` | `localhost` / `27017` / `root` / `rootpass` / `ubermongo` | Used when `mongosh` is on the host |
| `REDIS_VIA` / `MONGO_VIA` | `host` if CLI is on PATH, else `docker` | Force `host` or `docker` exec mode |
| `REDIS_CONTAINER` / `MONGO_CONTAINER` | `uber-redis` / `uber-mongo` | Container names for the docker fallback |
| `ES_URL`       | `http://localhost:9200` | Elasticsearch (S2-F11 ES doc check) |
| `NEO4J_URL` / `NEO4J_USER` / `NEO4J_PASSWORD` | `bolt://localhost:7687` / `neo4j` / `neo4jpass` | Neo4j (S3-F11 graph) |
| `CASSANDRA_HOST` / `CASSANDRA_PORT` / `CASSANDRA_KEYSPACE` | `localhost` / `9042` / `uberks` | Cassandra (S4-F12 timeline) |
| `RUN_ID` | `$(date +%s)$$` | Run-scope salt for unique fixtures (override only if you need a deterministic dataset) |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | `admin@uber.com` / `admin123` | Seeded admin for CC-2 tests (matches `user-service/DataSeeder.java`) |

---

## Output format

Every script emits one `PASS` / `FAIL` / `SKIP` line per assertion and
ends with a `TOTALS:` line. The orchestrator aggregates them:

```
##################################################################
# RUN  10-user-service.sh
##################################################################
==================================================================
# 10 user-service — S1-F10/F11/F12 + CC-2 + M1 + CRUD
==================================================================
  PASS  S1-F10 register A returns 201 (§10.1.1.g)  [201]
  PASS  S1-F10 token returned
  PASS  S1-F10 default role = RIDER (§4.2.c)
  ...
  FAIL  S1-F12 cross-user → 403 (NOT 404)
        expected status 403, got 404
        last status=404
        last body={"timestamp":"2026-04-30T...","status":404,...}
  ...

TOTALS: 47 PASS / 1 FAIL / 3 SKIP
```

`SKIP` means the assertion couldn't be verified (e.g. the M2 feature
isn't implemented yet, no ADMIN seed reachable, the assertion needs
wall-clock TTL waits). It is **not** a PASS — the orchestrator surfaces
it separately so you don't lose track.

---

## Idempotency

Every script derives unique fixtures from `RUN_ID="$(date +%s)$$"`
plus a `${RANDOM}${RANDOM}` salt per call, so repeated runs do not
collide on unique constraints (email, phone, license number, plate).
You can re-run the suite back-to-back without `docker compose down -v`.

If you've been running tests and Mongo / Cassandra contain stale event
data that the analytics tests aggregate over, run:

```bash
docker exec uber-mongo mongosh \
  "mongodb://root:rootpass@127.0.0.1:27017/ubermongo?authSource=admin" \
  --quiet --eval 'db.auth_events.deleteMany({}); db.driver_events.deleteMany({}); db.ride_events.deleteMany({}); db.location_events.deleteMany({}); db.payment_audit_trail.deleteMany({})'
docker exec uber-redis redis-cli -a redispass --no-auth-warning FLUSHALL
```

---

## Coverage caveats — features missing from the SUT

The endpoint catalog (run the `endpoint-cataloger` agent for a fresh
one) shows these M2 features were not yet implemented as of the branch
the suite was developed against:

- `S2-F10` GET /api/drivers/search/full-text
- `S2-F11` POST /api/drivers/{id}/index   (+ auto-index retrofit on CRUD)
- `S3-F10` GET /api/rides/analytics/dashboard
- `S3-F11` POST /api/rides/{rideId}/record-interaction
- `S3-F12` GET /api/rides/recommendations
- `S5-F10` GET /api/payments/analytics/vehicle-type
- `S5-F11` GET /api/payments/analytics/methods
- `PUT /api/users/{id}/role` (CC-2)  — security rule wired, controller missing
- `ride-service` still on `application.properties` (§9.6 violation)

The test cases for these features **already exist** in the suite —
when each controller is added, the corresponding assertions flip
from `FAIL` / `SKIP` to `PASS` automatically. **No script change needed.**

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Every assertion is 401 | JWT secret mismatch between services | Verify each `application.yml` (or `application.properties`) has the same `jwt.secret` |
| All assertions 503/500 right after startup | Service still booting (Spring takes ~60s) | `docker compose ps` and wait for healthy; re-run |
| `curl: (52) Empty reply from server` on port 8081 | user-service `server.port` doesn't match the container port the compose file forwards to | Add `SERVER_PORT: 8080` to the user-service `environment:` block in `docker-compose.yaml`, or change `server.port` in `user-service/src/main/resources/application.yml` to `8080` |
| `mongosh` / `redis-cli` not found | Tools not installed on host | Either install them (see Prerequisites) **or** ignore — the suite auto-falls-back to `docker exec` |
| `jq: command not found` | jq not on PATH (Windows) | `winget install jqlang.jq` then open a new shell. If still not found, symlink: `ln -sf "/c/Users/$USERNAME/AppData/Local/Microsoft/WinGet/Packages/jqlang.jq_Microsoft.Winget.Source_8wekyb3d8bbwe/jq.exe" /usr/bin/jq` |
| Redis assertions all FAIL | Wrong password / port | Override `REDIS_PASSWORD`, `REDIS_PORT` |
| Cache TTL test FAILs at 0 | Redis returned -2 (key gone — invalidated) | Expected on subsequent runs of the same script; ignore |
| S5-F12 NoRefundStrategy test FAILs | Server overrides client-provided `createdAt` | Insert the old payment via SQL instead, or skip case (g) on your stack |
| Background `docker compose up` produces no output | Docker Desktop not started | Open Docker Desktop and wait for whale icon to stop animating, then retry |
| `Authentication processing error` (500) on ride-service endpoints | ride-service is on `application.properties` (CC-6 violation) and not picking up `jwt.secret` correctly | Migrate ride-service to `application.yml` per §6.5 |
| Driver tests FAIL with `License number is required` (400) | An old version of the suite or hand-rolled curl is missing `licenseNumber` | Latest scripts include it; re-pull |
| 30-ride-service.sh has 30+ FAILs | All cascade from ride-service JWT failure | Fix ride-service config (above) — most FAILs disappear |

---

## Adding new tests

1. Pick the right file (`CC-*` for cross-cutting, `10-50` for the matching service).
2. `lib/common.sh` is already sourced.
3. Use the helpers: `http`, `http_auth`, `assert_status`, `assert_status_in`,
   `assert_json_field`, `assert_body_contains`, `redis_count_keys`,
   `redis_keys`, `redis_flush_pattern`, `mongo_count`, `mongo_eval`,
   `register_user`, `login_user`, `jwt_uid`, `jwt_role`.
4. **Tie every assertion to a spec citation** (e.g. `§10.5.3 step e`).
5. Run the file directly (`./tests/30-ride-service.sh`) before committing.

A new feature usually needs additions in two places:
- The per-service script (positive path + boundary + auth + cache).
- `03-cc-design-patterns.sh` if it touches Strategy / Observer / Builder / etc.

---

## File map

```
tests/
├── README.md                  # this file
├── run-all.sh                 # orchestrator (per-script + grand totals)
├── lib/
│   └── common.sh              # http/jwt/redis/mongo helpers, assertions
├── 00-health.sh               # 5 health endpoints (§5.4, §9.1)
├── 01-cc-jwt.sh               # CC-1 + CC-2 + DP-3 (§5, §9.1, §9.2, §3.4, §3.6)
├── 02-cc-cache.sh             # CC-3 cache contract (§4.4, §8)
├── 03-cc-design-patterns.sh   # CC-4 runtime hooks (§3.1–3.8, §4.5)
├── 04-cc-docker-yaml.sh       # CC-5 + CC-6 static checks (§6, §9.5, §9.6)
├── 10-user-service.sh         # S1-F10/F11/F12 + CC-2 + M1 S1-F1..F9 + CRUD
├── 20-driver-service.sh       # S2-F10/F11/F12 + auto-index + M1 S2-F1..F9 + CRUD
├── 30-ride-service.sh         # S3-F10/F11/F12 + recs graph + M1 S3-F1..F9 + CRUD
├── 40-location-service.sh     # S4-F10/F11/F12 + Cassandra + M1 S4-F1..F9 + CRUD
└── 50-payment-service.sh      # S5-F10/F11/F12 + 3 strategies + M1 S5-F1..F9 + CRUD
```

---

## End-to-End M3 JUnit Test Suite — Step-by-Step Guide

The JUnit suite sits **alongside** the bash scripts (which remain as stack-level smoke tests). JUnit is the deeper layer: every one of the **425 public TCs** from [scalable-docs.netlify.app/Uber_Tests_Description.md](https://scalable-docs.netlify.app/Uber_Tests_Description.md) is ported into per-service feature classes, adapted for M3 architecture (per-service Postgres, OpenFeign reads, RabbitMQ writes, gateway-injected `X-User-Id` headers).

**Current state**: 465 `@Test` methods across 77 classes covering all 5 services + design patterns + cross-cutting auth. Expected run profile (against a healthy SUT):

| Counter | Expected | What it means |
|---|---|---|
| Tests run | **465** | Total `@Test` methods discovered + executed |
| Passed | **~395** | ~96% pass rate of executable tests |
| Failures | **~19** | All real **SUT bugs** — see `tests/FAILURE-ANALYSIS.md` |
| Errors | **0** | Anything > 0 means a build/env problem (see Troubleshooting) |
| Skipped | **~46** | Intentional — see "Why are tests skipped?" |

---

### TL;DR — single-command first run

From the project root, with the docker-compose stack already up:

```bash
mvn -pl tests test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='*Test,*IT'
```

Single line, runs everything (saga `*IT` classes included).

---

### Step-by-step — full first-run from a cold start

#### Step 1 — Build all service jars

The Dockerfiles for each service copy a pre-built JAR (`<service>-0.0.1-SNAPSHOT.jar`). You must run Maven before `docker compose up --build` or the containers won't have anything to run.

```bash
cd /mnt/data/Repos/01-15-Reasons-Why-Uber
mvn clean package -DskipTests -T 4
```

`-T 4` runs 4 modules in parallel — usually finishes in 30–60 s.

#### Step 2 — Bring up the stack

```bash
docker compose up --build -d
```

This rebuilds the images using the JARs from Step 1, then starts all 17 containers (5 services + api-gateway + 5 PG + Mongo + Redis + RabbitMQ + Cassandra + Neo4j + Elasticsearch + pgadmin) in the background.

#### Step 3 — Wait for all services to be ready

Spring Boot containers report "Up" within seconds but take 30–90 s to finish loading. Poll the health endpoints until all 5 return `OK`:

```bash
for entry in 8081:users 8082:drivers 8083:rides 8084:locations 8085:payments; do
  port=${entry%:*}; svc=${entry#*:}
  printf "port %s (%s): " "$port" "$svc"
  until curl -fsS "localhost:$port/api/$svc/health" 2>/dev/null; do sleep 2; done
  echo OK
done
```

If a service never goes healthy, `docker compose logs --tail=80 <service-name>` will show why.

#### Step 4 — Compile the test module

Forces a clean test-compile so no stale `target/test-classes` lingers (the common cause of `NoClassDefFoundError` — see Troubleshooting):

```bash
mvn -pl tests clean test-compile
```

#### Step 5 — Run the full suite

```bash
mvn -pl tests test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='*Test,*IT'
```

Why the `-Dtest='*Test,*IT'` override? Surefire's default `<include>` patterns match `*Test`/`*Tests`/`Test*`/`*TestCase` but **not** `*IT`. The saga integration tests use the `*IT` suffix, so without this flag they're silently skipped. Approximate runtime: 5–7 min total.

Surefire writes XML + text reports to `tests/target/surefire-reports/` for any deeper inspection (per-method timing, stack traces, etc.).

---

### Useful commands

| Goal | Command |
|---|---|
| Single service | `mvn -pl tests test -Dtest='com.team01.uber.tests.driver.*' -Dsurefire.failIfNoSpecifiedTests=false` |
| Single feature class | `mvn -pl tests test -Dtest=RegisterFeatureTest -Dsurefire.failIfNoSpecifiedTests=false` |
| Single TC method | `mvn -pl tests test -Dtest='RegisterFeatureTest#tc01_freshEmail_returns2xxAndJwt'` |
| Skip slow saga tests | `mvn -pl tests test -Dgroups='!saga'` |
| Only saga tests | `mvn -pl tests test -Dtest='*SagaIT' -Dgroups='saga' -Dsurefire.failIfNoSpecifiedTests=false` |
| Override host (e.g., gateway) | `mvn -pl tests test -Dservice.user.base=http://localhost:30080` |

---

### Interpreting the results

The final tally line looks like:

```
Tests run: 465, Failures: 19, Errors: 0, Skipped: 46
```

- **Failures** = **real SUT bugs**. Each line is `<class>.<method>:<line> [<as>] expected: X but was: Y`. Every failure is documented in `tests/FAILURE-ANALYSIS.md` with a root-cause hypothesis + fix suggestion citing the M3 spec.
- **Errors** = test-infrastructure problems (compilation, classpath, network). A clean run should have **0 errors**. If you see `NoClassDefFoundError`, see Troubleshooting below.
- **Skipped** = intentional `@Disabled` or `Assumptions.assumeTrue(...)` skips. ~33 are structural design-pattern TCs (require reflection on a service's internal classpath — covered by the bash layer instead). ~10 are direct-DB-access payment TCs (need `docker exec psql`). ~3 are environmental (e.g., catalogue-mandated skips).

The full per-failure report is at **`tests/REPORT.md`** (auto-generated by the verifier agent). Detailed root-cause + fix suggestions per SUT bug are at **`tests/FAILURE-ANALYSIS.md`**.

---

### File map (current state)

```
tests/src/test/java/com/team01/uber/tests/
├── BaseHttpTest.java          # USER_BASE..PAYMENT_BASE constants
├── BaseUnitTest.java          # @ExtendWith(MockitoExtension.class) for non-HTTP cases
├── fixtures/                  # shared, package-public helpers
│   ├── Http.java              # fluent HTTP helper (java.net.http.HttpClient)
│   ├── Nonce.java             # unique email/phone seeds
│   ├── JwtClaims.java         # parse uid/role/email out of a token
│   ├── JwtTestHelper.java     # issue valid/expired tokens (uses shared contracts.JwtConfigurationManager)
│   ├── GatewayHeaders.java    # X-User-Id / X-User-Role / X-Correlation-ID injection
│   ├── Mongo.java             # count(collection, filter) + countAtLeast polling
│   ├── Redis.java             # keys(pattern) + countKeys + ttl + exists
│   ├── Rabbit.java            # queueDepth / dlqDepth via RabbitMQ mgmt API (port 15672)
│   ├── Seeders.java           # registerRider / adminTokenOrNull / seedDriver / seedRide / seedPayment
│   └── Eventually.java        # await(Duration, BooleanSupplier) — async polling
├── user/                      # 16 classes — TC01..TC34, TC191..TC220, TC329..TC376
├── driver/                    # 14 classes — TC35..TC53 (S2-F10/F11/F12) + M1 regression
├── ride/                      # 15 classes — TC54..TC99 (S3-F4/10/11/12) + Saga A IT
├── location/                  # 11 classes — TC100..TC135 + S4-F9..F12 + M1 regression
├── payment/                   # 16 classes — TC136..TC190 + S5-F9..F12 + Sagas B & C ITs
├── designpatterns/            # 7 classes — TC379..TC425 (DP-1..DP-7)
└── crosscutting/              # 3 classes — TC06..TC13 cross-service JWT
```

---

### Why are tests skipped? (the ~46 Skipped breakdown)

Three intentional buckets:

| Bucket | Count | Skip mechanism | Why |
|---|---|---|---|
| Structural DP tests | ~33 | `@Disabled("DEFERRED: structural reflection...")` | Need Java reflection on service classpaths the JUnit module doesn't have. Covered by `tests/03-cc-design-patterns.sh` + the autograder. |
| Direct-DB-access payment TCs | ~10 | `@Disabled("DEFERRED: requires JDBC UPDATE...")` | Need `UPDATE payments.created_at` or `Mongo.insertOne` to seed edge cases. Covered by `tests/50-payment-service.sh` via `docker exec psql`/`mongosh`. |
| Admin-seed gated | ~3 | `Assumptions.assumeTrue(adminToken != null, ...)` | Skip when SUT has no admin user seeded. Auto-recovers when seed exists. |

**These are NOT cascading failures** — they would skip on a perfectly healthy SUT too.

---

### Conventions

- One test class per **feature** (matches the public TC catalogue's grouping).
- Test method name `tc<NN>_<short_camel_case>`.
- `@DisplayName` carries the verbatim TC title from the public doc.
- Assertions are **spec-strict** — when a SUT bug is suspected, the test should FAIL, not skip. SUT-bug workarounds (relaxed `isIn(...)`, `@Disabled` for SUT bugs, `Assumptions.assumeTrue(... != 503)` masks) are not allowed — every catch goes in `tests/FAILURE-ANALYSIS.md` instead.
- Cross-service reads tested by hitting the real service via HTTP (Feign is transparent to black-box tests).
- Cross-service writes verified via the RabbitMQ management API on port 15672 (see `fixtures/Rabbit.java`).
- Observer-pattern writes verified by polling Mongo collections (`auth_events`, `driver_events`, `ride_events`, `location_events`, `payment_audit_trail`) — see `fixtures/Mongo.java`.

---

### Troubleshooting

#### "NoClassDefFoundError: com/team01/uber/tests/{ride/RideTestSupport,user/UserSeederSupport,…}"

Stale `target/test-classes`. Force a clean test-compile:

```bash
mvn -pl tests clean test-compile
ls tests/target/test-classes/com/team01/uber/tests/ride/RideTestSupport.class   # should exist
ls tests/target/test-classes/com/team01/uber/tests/user/UserSeederSupport.class # should exist
mvn -pl tests test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='*Test,*IT'
```

#### Wholesale "connection reset" / "connection refused" errors (100+ at once)

The stack isn't ready yet. Spring Boot apps take 30–90 s after the container reports "Up" before they accept requests. Wait for all 5 `/health` endpoints to return `OK` (Step 3 above) before running tests.

#### "Saga A hop 2 timed out" or "RideStatus did not flip to PAID"

Either the saga is genuinely broken in the SUT (see `tests/FAILURE-ANALYSIS.md` Category E/F) or RabbitMQ is restarting. Check `docker compose logs --tail=30 rabbitmq` and `docker compose logs --tail=30 payment-service`.

#### Mongo / Redis / Rabbit not reachable from the test JVM

The fixtures connect to `localhost:27017` (Mongo), `localhost:6379` (Redis), `localhost:15672` (RabbitMQ mgmt). All are exposed by `docker-compose.yaml`. Verify:

```bash
docker exec uber-mongo mongosh "mongodb://root:rootpass@127.0.0.1:27017/ubermongo?authSource=admin" --quiet --eval 'db.getCollectionNames()'
docker exec uber-redis redis-cli -a redispass --no-auth-warning DBSIZE
curl -fsS -u guest:guest http://localhost:15672/api/queues | jq 'length'
```

#### Mongo state accumulates between runs and breaks aggregation TCs

Tests use `Nonce` salts so per-test state stays unique, but global counters (e.g., `db.location_events` totals) can drift over many runs. Optional cleanup:

```bash
docker exec uber-mongo mongosh "mongodb://root:rootpass@127.0.0.1:27017/ubermongo?authSource=admin" \
  --quiet --eval 'db.auth_events.deleteMany({}); db.driver_events.deleteMany({}); db.ride_events.deleteMany({}); db.location_events.deleteMany({}); db.payment_audit_trail.deleteMany({})'
docker exec uber-redis redis-cli -a redispass --no-auth-warning FLUSHALL
```

#### Fresh start (wipe everything)

```bash
docker compose down -v          # wipes volumes — fresh DBs
mvn clean package -DskipTests -T 4
docker compose up --build -d
# Step 3 (health-wait) + Step 4 + Step 5 from above
```

---

### Related artefacts

- **`tests/REPORT.md`** — auto-generated per-failure report with stack traces, last HTTP status, and a one-line root-cause hypothesis per failure.
- **`tests/FAILURE-ANALYSIS.md`** — detailed root-cause analysis with M3 spec citations and concrete fix suggestions per failure category (A–K).
- **`tests/sut-bugs/<service>-sut-bugs.md`** — known SUT bugs that the bash test layer catches (predates JUnit; JUnit catches the same ones plus several more).
