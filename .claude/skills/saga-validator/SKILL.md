---
name: saga-validator
description: Run the M3 ride-lifecycle saga test scenarios A/B/C end-to-end against the live MiniKube cluster (or docker-compose fallback) per uber-m3.md §8.6. Verifies pre-saga Feign checks, RabbitMQ event publish/consume, the 5-hop async compensation cascade in B, and the no-event abort path in C. Read-only on the codebase; produces a structured PASS/FAIL report.
---

# Saga Validator

You are running the three saga test scenarios from `docs/m3/uber-m3.md` §8.6 (lines 1362–1387) against the live cluster. The tests are end-to-end: trigger the M3 saga at S3, observe the event ripple at S1/S2/S4/S5 and (in B) the compensation cascade reversing back to S3.

## Sources of Truth (Read First)

1. **`docs/m3/saga-events.md`** — full saga topology, payloads, A/B/C scenario specs.
2. **`docs/m3/uber-m3.md` §8** — original spec (lines 1207–1397).
3. **`docs/m3/feign-contracts.md`** — the three S3-F4 pre-check Feign endpoints.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/saga-events.md`, `docs/m3/feign-contracts.md` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Stack defaults

M3 grading surface is MiniKube (uber-m3.md:2615). Configurable via env var:

```bash
GATEWAY_URL="${GATEWAY_URL:-http://$(minikube ip):30080}"
RABBIT_HOST="${RABBIT_HOST:-rabbitmq.uber.svc.cluster.local}"   # use kubectl port-forward locally
```

## Step 1: Pre-flight

- Cluster is up: `kubectl get pods -n uber` shows all Running.
- Gateway reachable: `curl -fs "${GATEWAY_URL}/api/users/health"` returns `OK`.
- RabbitMQ reachable: `kubectl exec -n uber svc/rabbitmq -- rabbitmqctl list_exchanges name | grep -E 'user.events|driver.events|ride.events|location.events|payment.events'`. All 5 must be present.

If any fails: `STACK NOT READY — start the cluster (kubectl apply -f k8s/...) before running.` Do NOT proceed.

## Step 2: Token

Acquire a JWT for an ACTIVE user. Either reuse a seeded test user or register one via `POST /api/auth/register`.

## Step 3: Scenario A — Happy path (uber-m3.md:1364–1372)

1. **Setup:** insert/upsert via the gateway-routed CRUD or direct port-forwarded SQL:
   - User ID=1 status=ACTIVE in user-postgres.
   - Driver ID=5 status=BUSY (assigned to Ride ID=10) in driver-postgres.
   - Ride ID=10 status=IN_PROGRESS, userId=1, driverId=5, fare=null in ride-postgres.
   - Location for driver 5 with timestamp 1 minute ago in location-postgres.
2. **Action:** `PUT ${GATEWAY_URL}/api/rides/10/complete`.
3. **Expect:** 200. Ride status = COMPLETED. `ride.completed` event published.
4. **Verify:** poll `GET /api/rides/10` until status = PAYMENT_PENDING (or wait ≥ 1s). Driver 5 status = AVAILABLE with totalEarnings incremented. Payment row for rideId=10 with amount=fare in PENDING state.
5. **Action:** `POST ${GATEWAY_URL}/api/payments/ride/10` with `{"method": "CREDIT_CARD", "cardLastFour": "4242"}`.
6. **Expect:** 201. `payment.completed` event published.
7. **Verify:** poll `GET /api/rides/10` until status = PAID, or wait ≥ 1s.

## Step 4: Scenario B — Payment failure compensation (uber-m3.md:1374–1379)

1. **Setup:** rerun A through step 4 (reach Ride status = PAYMENT_PENDING with PENDING payment).
2. **Action:** `POST ${GATEWAY_URL}/api/payments/ride/10` with `{"method": "BITCOIN"}` — deliberately unsupported method to force failure.
3. **Expect:** 400. `payment.failed` event published.
4. **Verify the 5-hop async cascade:**
   - `payment.failed` → S3 sets Ride = PAYMENT_FAILED.
   - S3 publishes `ride.cancelled` with `reason="payment_failed"`.
   - S1/S2/S4/S5 consumers reverse their state.
   - S5 issues refund and publishes `payment.refunded`.
   - S3 sets Ride = REFUNDED.

   **Poll `GET ${GATEWAY_URL}/api/rides/10` until `status = REFUNDED`, or wait ≥ 3s** for the full cascade (uber-m3.md:1379).

5. **Assert:**
   - `ride.cancelled` was published with `reason="payment_failed"` (verify in RabbitMQ via `rabbitmqctl list_queues messages_ready` count delta or by tailing a debugging queue).
   - Rider stats reversed.
   - Driver stats reversed (driver back to AVAILABLE if still BUSY).
   - payment-postgres Payment row status = REFUNDED.
   - ride-postgres Ride status = REFUNDED.

## Step 5: Scenario C — Pre-check failure (uber-m3.md:1381–1386)

1. **Setup:** User ID=1 (ACTIVE), Driver ID=5 (BUSY), Ride ID=10 (IN_PROGRESS). Latest location for driver 5 has a timestamp from **30 minutes ago** (stale).
2. **Action:** `PUT ${GATEWAY_URL}/api/rides/10/complete`.
3. **Expect:** 400 — location-service `GET /api/locations/driver/5/recent` returns 404 because the latest ping is older than 5 minutes. **S3 aborts before publishing any event.**
4. **Assert:**
   - **No `ride.completed` event in RabbitMQ.** Verify via `rabbitmqctl list_queues messages_ready` — the `driver.ride.completed`, `user.ride.completed`, `location.ride.completed`, `payment.ride.completed` queues all have the same depth as before the action.
   - Ride status still = IN_PROGRESS.
   - Driver still BUSY.

## Step 6: Cleanup

Delete the test user, ride, payment, driver via the gateway-routed CRUD endpoints. Don't try to clean up Mongo audit collections — those are expected to accumulate.

## Step 7: Report

```
saga-validator report
═════════════════════
Cluster:                <PASS / NOT READY>
Token:                  <reused / generated>

Scenario A — Happy path:
  Pre-checks pass:                   PASS / FAIL
  ride.completed published:          PASS / FAIL
  Driver flipped AVAILABLE:          PASS / FAIL
  Payment created PENDING:           PASS / FAIL
  payment.completed published:       PASS / FAIL
  Ride flipped PAID:                 PASS / FAIL

Scenario B — Payment failure cascade:
  Reached PAYMENT_PENDING:           PASS / FAIL
  payment.failed published:          PASS / FAIL
  ride.cancelled published:          PASS / FAIL
  Rider stats reversed:              PASS / FAIL
  Driver back to AVAILABLE:          PASS / FAIL
  payment.refunded published:        PASS / FAIL
  Ride flipped REFUNDED:             PASS / FAIL

Scenario C — Pre-check failure:
  400 returned:                      PASS / FAIL
  No ride.completed in queues:       PASS / FAIL
  Ride still IN_PROGRESS:            PASS / FAIL
  Driver still BUSY:                 PASS / FAIL

Overall: PASS / FAIL
```

## Constraints

- **Read-only on the codebase.** No edits.
- **Always clean up** test entities. Mongo audit docs left alone.
- **Don't try to fix bugs.** If a step FAILs, report the failure with a fix hint and stop.
- **Flag flakiness explicitly.** If Scenario B's cascade hasn't completed at 3s, retry once with a 5s wait and label the result `SLOW PASS` or `FAIL (timeout)`.
