---
name: db-isolation-bootstrap
description: Wire (or verify) per-service PostgreSQL isolation per uber-m3.md §1 — each service connects to its own PG instance (uberdb-users, uberdb-drivers, uberdb-rides, uberdb-locations, uberdb-payments). Removes cross-service @ManyToOne (becomes plain Long) and any cross-DB native @Query JOIN. Generates the K8s StatefulSet stub for the service's own PG. Critical Rule #1 — "No cross-service JDBC. Zero tolerance" (uber-m3.md:2635).
---

# Database Isolation Bootstrap

You are wiring (or auditing) the M3 per-service PostgreSQL isolation. The M1/M2 shared `postgres:5432/uberdb` URL is gone — each of the 5 services now points at its own `<svc>-postgres:5432/uberdb-<svc>s`.

## Critical anchor (uber-m3.md:2635)

> **No cross-service JDBC.** After M3, no service opens a JDBC connection to another service's database. Zero tolerance.

Cross-service reads → `feign-bootstrap`. Cross-service writes → `rabbitmq-bootstrap`. This skill only handles the local-PG isolation.

## Sources of Truth (Read First)

1. **`docs/m3/uber-m3.md` §1** — what changes, the 5 datasource URLs (lines 65–100), and the FK-flattening table (lines 106–117).
2. **`docs/m3/yaml-fragments/<service>.application.yml`** — copy-paste reference for the new datasource block.
3. **`docs/m3/k8s-manifests.md` §10.4** — `<svc>-postgres` StatefulSet template.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/k8s-manifests.md`, `docs/m3/yaml-fragments/<svc>.application.yml` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Step 1: Identity + Branch

Confirm developer + ID. Pick a service.

```
git checkout main && git pull origin main
git checkout -b chore/M3/<scope>/db-isolation-<service>/<studentId>
```

`<scope>` is the service shortname (`user`, `driver`, `ride`, `location`, `payment`).

## Step 2: Update `application.yml`

Replace the shared datasource line with the per-service one. Verify via `docs/m3/yaml-fragments/<service>.application.yml`. Examples (uber-m3.md:65–100):

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://<svc>-postgres:5432/uberdb-<svc>s}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
```

`<svc>-postgres` is the K8s `serviceName` of this service's PG StatefulSet. The default fallback is for local dev with port-forward; the env var wins in K8s (injected from the per-service ConfigMap, uber-m3.md:1597).

## Step 3: Flatten cross-service `@ManyToOne` to `Long`

Per uber-m3.md:104:

> Every `@ManyToOne` or `@JoinColumn` that pointed to another service's entity becomes a plain `Long` field.

The per-service flattening table is at uber-m3.md:106–117. Most `Long` columns were already plain in M1/M2 (rides.user_id, rides.driver_id, locations.driver_id, payments.ride_id, payments.user_id) — the few that crept in via M2 retrofits must be flattened.

Intra-service `@ManyToOne` relationships **stay JPA-managed** (uber-m3.md:117): `SavedAddress→User`, `DriverDocument→Driver`, `RideStop→Ride`, `PaymentCoupon→Payment`, `PaymentCoupon→Coupon`. Don't accidentally flatten those.

Source-scan to find offenders:

```
grep -rEn "@ManyToOne|@JoinColumn" <service>/src/main/java/ --include='*.java'
```

For each cross-service hit, replace the relationship with a plain `Long <foreign>Id;` field. Update getters/setters accordingly. Repository custom queries that joined across services must also go (Step 4).

## Step 4: Remove cross-service native SQL `@Query`

Source-scan:

```
grep -rEn '@Query.*nativeQuery\s*=\s*true' <service>/src/main/java/ --include='*.java'
```

For each hit, read the SQL and check whether the `FROM` / `JOIN` references a table outside this service's PG (e.g., a `payments` table in user-service, a `users` table in payment-service). Such queries must be **removed** — the data they fetched now comes from a Feign call (set up via `feign-bootstrap`).

Critical Rule #1 (uber-m3.md:2635): "Zero tolerance" on cross-service JDBC. The grader will explicitly fail this.

## Step 5: K8s StatefulSet stub

Generate (or verify) the service's PG StatefulSet under `k8s/statefulsets/<svc>-postgres-statefulset.yaml`. Template lives at `docs/m3/k8s-manifests.md` (the §10.4 block). Memory cap `512Mi`, `pg_isready` probe (uber-m3.md:1750).

Also generate the matching headless Service at `k8s/services/<svc>-postgres-svc.yaml` and the Secret at `k8s/secrets/<svc>-postgres-secret.yaml` containing `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`. PVC at `k8s/pvcs/<svc>-postgres-pvc.yaml`.

## Step 6: Update the service's `<service>-configmap.yaml`

Add `SPRING_DATASOURCE_URL: jdbc:postgresql://<svc>-postgres:5432/uberdb-<svc>s` (uber-m3.md:1597). The Deployment's `envFrom.configMapRef` will inject it.

## Step 7: Verify mode

If invoked in verify mode (`db-isolation-bootstrap verify <service>`):

- `application.yml` datasource matches the expected `<svc>-postgres:5432/uberdb-<svc>s` pattern. **FAIL** if it still contains `postgres:5432/uberdb`.
- Source-scan: zero cross-service `@ManyToOne` / `@JoinColumn`.
- Source-scan: zero native `@Query` joining across services.
- K8s stubs (StatefulSet, headless Service, Secret, PVC) all present and well-formed.

## Constraints

- **Never reveal AI authorship.** No trailers/tags on commits or in code.
- **Never push, merge, or open a PR directly.** Stage commands; the user runs them.
- This skill does not call `feign-bootstrap` automatically — when this skill removes a cross-service `@Query`, dispatch `feign-bootstrap` next to wire the replacement Feign call.
