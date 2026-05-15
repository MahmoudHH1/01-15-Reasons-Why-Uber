---
name: nosql-bootstrap
description: Wire (or verify) NoSQL clients per service — MongoDB everywhere, Elasticsearch in driver-service, Neo4j in ride-service, Cassandra in location-service, Redis everywhere. Carries over from M2 to M3 unchanged (uber-m3.md:41 — "All 6 M2 databases [...]"); NoSQL stores remain a shared instance per uber-m3.md §1.3 even though PostgreSQL is now per-service. Has two modes — bootstrap (create wiring + skeletons) and verify (check existing wiring).
---

# NoSQL Bootstrap

You are wiring (or auditing) NoSQL clients. The set of stores and the per-service ownership carry over from M2 to M3 unchanged — `docs/m3/uber-m3.md:41` says "All 6 M2 databases (PostgreSQL + MongoDB + Redis + Elasticsearch + Neo4j + Cassandra)" carry over, and uber-m3.md §1.3 confirms NoSQL stores remain shared instances logically isolated by collection/keyspace.

| Service | NoSQL clients required |
|---|---|
| user-service | MongoDB, Redis |
| driver-service | MongoDB, Redis, **Elasticsearch** |
| ride-service | MongoDB, Redis, **Neo4j** |
| location-service | MongoDB, Redis, **Cassandra** |
| payment-service | MongoDB, Redis |

> **Important M3 distinction:** PostgreSQL is **not** in this skill's scope. Per-service PG datasource isolation is handled by `db-isolation-bootstrap`. This skill only wires the NoSQL stores above, which remain a single shared cluster.

## Sources of Truth (Read First)

1. **`docs/m3/yaml-fragments/<service>.application.yml`** — copy-paste reference for the exact config block this service needs. Use it as the starting template in Step 4 below rather than retyping by hand. The PG datasource line in those fragments points at `<svc>-postgres:5432/uberdb-<svc>s` — leave that to `db-isolation-bootstrap`; this skill only touches the NoSQL blocks.
2. **`docs/m3/event-actions.md`** — canonical Mongo action vocabularies + RabbitMQ routing keys. The audit step (Step 8f) cross-checks against the Mongo column.
3. **`Uber_descriptionM2.pdf` §6, §7** — original entity tables, image tags, memory caps for the NoSQL stores. Use `spec-clause-finder --milestone m2` for verbatim clauses.
4. **`docs/m3/uber-m3.md` §1.3, §10.8** — the M3-specific notes about shared NoSQL ownership and K8s memory caps.

If the doc and this skill disagree, trust the doc and flag the drift.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/event-actions.md`, `docs/m3/yaml-fragments/<svc>.application.yml` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Step 1: Choose Mode

Ask the user (use AskUserQuestion):

1. **Bootstrap** — wire NoSQL clients into a service that doesn't have them yet (creates pom deps, application.yml fragments, document/entity/repository skeletons).
2. **Verify** — audit an existing wiring against the spec; produce a PASS/FAIL report and list specific gaps.

Then ask which service.

## Step 2 (Bootstrap mode): Identity + Branch

Confirm developer name + ID. Create a branch under the M3 §13.1 format:

```
git checkout main && git pull origin main
git checkout -b chore/M3/cc/nosql-<service>/<studentId>
```

(Use `cc` scope because NoSQL wiring is a cross-cutting requirement.)

## Step 3 (Bootstrap mode): Pom Dependencies

Add to the service's `pom.xml` based on which NoSQL clients it needs:

```xml
<!-- MongoDB (all services) -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>

<!-- Redis (all services) -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Elasticsearch — driver-service only -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>

<!-- Neo4j — ride-service only -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>

<!-- Cassandra — location-service only -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-cassandra</artifactId>
</dependency>
```

Important: Spring Boot 4.0.3 uses Jackson 3.x via `tools.jackson.*`. Hibernate 7.2 JSONB still needs Jackson 2.x for FormatMapper only — keep the existing M1 dual dependency; do NOT remove `com.fasterxml.jackson.*` deps.

## Step 4 (Bootstrap mode): application.yml Fragments

Add the per-service fragments from §6.5. Reference shapes:

**Shared (all services)**

```yaml
spring:
  data:
    redis:
      host: redis
      port: 6379
      password: redispass
    mongodb:
      uri: mongodb://root:rootpass@mongo:27017/ubermongo?authSource=admin
jwt:
  secret: "<base64-encoded-shared-secret-≥32-bytes>"
  expiration: 86400000   # 24h
```

**driver-service only**

```yaml
spring:
  elasticsearch:
    uris: http://elasticsearch:9200
```

**ride-service only**

```yaml
spring:
  data:
    neo4j:
      uri: bolt://neo4j:7687
      username: neo4j
      password: neo4jpass
```

**location-service only**

```yaml
spring:
  cassandra:
    contact-points: cassandra
    port: 9042
    local-datacenter: datacenter1
    keyspace-name: uberks
    schema-action: CREATE_IF_NOT_EXISTS
```

If the service still has `application.properties`, migrate to `application.yml` first (CC-6 — auto-grader requires YAML).

## Step 5 (Bootstrap mode): Entity / Document Skeletons

Generate the new types per §7 (must be **classes, not records** for Mongo).

### MongoDB — every service

`MongoEvent` interface (defined once in a shared place or replicated per service per the spec — services must each own their logger):

```java
public interface MongoEvent {
    String getId();
    LocalDateTime getTimestamp();
    String getAction();
    Map<String, Object> getDetails();
}
```

Then the service-specific event class. For example user-service `AuthEvent`:

```java
@Document(collection = "auth_events")
public class AuthEvent implements MongoEvent {
    @Id private String id;
    private Long userId;
    private String action;
    private LocalDateTime timestamp;
    private Map<String, Object> details;
    // getters; NO @Component; class form, not record
}
```

Per service:

| Service | Class | Collection | Distinct fields |
|---|---|---|---|
| user-service | `AuthEvent` | `auth_events` | `userId:Long` |
| driver-service | `DriverEvent` | `driver_events` | `driverId:Long` |
| ride-service | `RideEvent` | `ride_events` | `rideId:Long` |
| location-service | `LocationEvent` | `location_events` | `driverId:Long` |
| payment-service | `PaymentAuditEvent` | `payment_audit_trail` | `paymentId:Long`, `method:String`, `amount:Double` |

Repository skeleton per service:

```java
public interface AuthEventRepository extends MongoRepository<AuthEvent, String> {
    Page<AuthEvent> findByUserIdOrderByTimestampDesc(Long userId, Pageable pageable);
}
```

### Elasticsearch — driver-service

```java
@Document(indexName = "drivers")
public class DriverSearchDocument {
    @Id private String id;
    @Field(type = FieldType.Text) private String name;
    @Field(type = FieldType.Keyword) private String vehicleType;
    @Field(type = FieldType.Text) private String description;
    @Field(type = FieldType.Double) private Double rating;
    @Field(type = FieldType.Keyword) private String status;
}
```

Repository:

```java
public interface DriverSearchRepository extends ElasticsearchRepository<DriverSearchDocument, String> { }
```

### Neo4j — ride-service

```java
@Node("User")
public class UserNode {
    @Id private Long userId;
    private String name;
}

@Node("Driver")
public class DriverNode {
    @Id private Long driverId;
    private String name;
    private String vehicleType;
}

@RelationshipProperties
public class RodeWith {
    @RelationshipId private Long id;
    @TargetNode private DriverNode driver;
    private Integer rideCount;
    private LocalDateTime lastRideDate;
}
```

The relationship name is **`RODE_WITH`** (note: PDF uses RODE_WITH; M2 description has a single-spot typo — verify against the PDF, but the canonical answer per §7.3.3 is `RODE_WITH`).

### Cassandra — location-service

```java
@Table("location_tracking_events")
public class LocationTrackingEvent {
    @PrimaryKeyColumn(name="driver_id", type=PrimaryKeyType.PARTITIONED)
    private Long driverId;
    @PrimaryKeyColumn(name="timestamp", type=PrimaryKeyType.CLUSTERED, ordering=Ordering.DESCENDING)
    private Instant timestamp;
    private Double latitude;
    private Double longitude;
    private Double speed;
    private Double heading;
    private Double accuracy;
    private Long rideId;
    private String notes;
}
```

Reminder: every Cassandra query must include `driver_id` in WHERE.

## Step 6 (Bootstrap mode): Soft-dependency Wiring

Per §6.3, MongoDB / Redis / ES / Neo4j / Cassandra are **soft** deps — service must still boot with them down. Wire `try/catch` around NoSQL access in service-layer code so failures `log.warn` and degrade gracefully. Do NOT make the boot dependent on NoSQL.

For Mongo specifically: `MongoEventLogger` must catch any Mongo exception, `log.warn`, and **not rethrow** — the upstream Postgres tx must not roll back on a Mongo write failure.

## Step 7 (Bootstrap mode): Smoke Test

```
mvn -pl <service> -am clean package -DskipTests
docker compose up -d
```

Wait for healthy state. Then:

- **Mongo**: `mongosh mongodb://root:rootpass@localhost:27017/ubermongo?authSource=admin --eval 'db.runCommand({ping:1})'`
- **Redis**: `redis-cli -a redispass ping` → PONG
- **ES** (driver-service): `curl -s http://localhost:9200/_cluster/health | jq`
- **Neo4j** (ride-service): `cypher-shell -u neo4j -p neo4jpass 'CALL dbms.components();'`
- **Cassandra** (location-service): `cqlsh -e 'DESCRIBE KEYSPACES'`

Boot the service in isolation: stop NoSQL, start the service alone (PG up). It must reach health.

## Step 8 (Verify mode): Audit Existing Wiring

When the user picks **Verify**, run a read-only audit. For the chosen service:

### 8a. pom.xml deps

Confirm the right starters are present (per the table at top). Flag any missing.

### 8b. application.yml

- Confirm `application.yml` exists (not `application.properties`) — CC-6.
- Confirm the right per-service section (mongo URI, redis host, jwt secret/expiration always; ES URI for driver; neo4j URI for ride; cassandra contact-points + keyspace-name for location).
- Confirm `spring.datasource.url` still points to `postgres:5432`.

### 8c. Document / entity classes match §7

For each NoSQL store the service uses, confirm:

- The right document/entity class exists with the right field set and types.
- MongoDB events are **classes, not records**.
- Mongo event classes implement `MongoEvent` with the four interface methods (id/timestamp/action/details).
- Driver service `DriverSearchDocument` has the 6 fields with the right ES field types (Keyword vs Text on description/name).
- Ride service has `UserNode`, `DriverNode`, `RodeWith` (or relationship-properties equivalent) and the relationship is named `RODE_WITH`.
- Location service `LocationTrackingEvent` has `driver_id` as partition key and `timestamp` as clustering DESC.
- PaymentAuditEvent has `method:String` and `amount:Double` on top of the common interface.

### 8d. Repositories

Confirm one repository interface per document class. For the Mongo events: confirm a sortable / paginated query method exists if the service has a feature that needs it (e.g., `findByUserIdOrderByTimestampDesc` for S1-F12).

### 8e. Soft-dep graceful boot

- Check service config for `MongoEventLogger` (or whatever logger): wraps writes in try/catch + `log.warn`.
- Boot the service with NoSQL stopped — must still reach health. Run this live:

```
docker compose stop mongo redis elasticsearch neo4j cassandra
mvn -pl <service> spring-boot:run    # in another terminal
curl -f http://localhost:<port>/api/<svc>/health   # must return 200 OK
docker compose start mongo redis elasticsearch neo4j cassandra
```

### 8f. Action vocabulary

Cross-check the service's events against the action vocabulary tables in §7.1 (UPPER_SNAKE_CASE only). Examples:

- `auth_events.action`: REGISTERED, LOGGED_IN, ROLE_CHANGED, USER_UPDATED, USER_DEACTIVATED, DEFAULT_ADDRESS_SET, USER_CREATED, USER_DELETED.
- `driver_events.action`: INDEXED, UPDATED, DASHBOARD_VIEWED, VEHICLE_DETAILS_UPDATED, AVAILABILITY_UPDATED, RATING_RECORDED, DOCUMENT_VERIFIED, DRIVER_CREATED, DRIVER_DELETED.
- `ride_events.action`: ANALYTICS_VIEWED, INTERACTION_RECORDED, DRIVER_ASSIGNED, RIDE_COMPLETED, RIDE_CANCELLED, STOPS_ADDED, RIDE_CREATED, RIDE_DELETED.
- `location_events.action`: TRACKING_RECORDED, ANALYTICS_VIEWED, LOCATION_UPDATED, BATCH_LOCATIONS_UPDATED, OLD_LOCATIONS_PURGED, LOCATION_DELETED.
- `payment_audit_trail.action`: CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, ANALYTICS_VIEWED, COUPON_APPLIED, RETRY_ATTEMPTED, PAYMENT_DELETED. **method/amount required on payment-shaped actions** (CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED).

### 8g. Verify report format

```
NoSQL Wiring Audit — <service>
══════════════════════════════
Pom dependencies:        [PASS/FAIL]
application.yml:         [PASS/FAIL]   (config format + needed sections)
Mongo document class:    [PASS/FAIL]
Mongo repository:        [PASS/FAIL]
Mongo soft-dep handling: [PASS/FAIL]
ES document (driver):    [PASS/FAIL/N/A]
Neo4j entities (ride):   [PASS/FAIL/N/A]
Cassandra entity (loc):  [PASS/FAIL/N/A]
Action vocabulary:       [PASS/WARN]   (warn on unrecognized actions; spec is non-exhaustive)
Boot in isolation:       [PASS/FAIL]

Overall: READY / NOT READY
```

For each FAIL, give the exact file path + line + missing/wrong content + the spec section that mandates it.
