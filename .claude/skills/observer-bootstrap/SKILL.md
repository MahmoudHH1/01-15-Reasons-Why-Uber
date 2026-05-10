---
name: observer-bootstrap
description: Wire the classical GoF Observer chain per service — EntityObserver interface, MongoEventLogger bound to a fixed EventType, Subject mixin/abstract base with register/unregister/notifyObservers, EventFactory dispatch, and one wired demo write. Carries over from M2 to M3 unchanged (uber-m3.md:44 — "MongoDB event logging (Observer pattern stays in place)"); coexists with M3's RabbitMQ event surface, which has its own state-guarded consumers. Replaces ad-hoc Mongo writes scattered across services.
---

# Observer Bootstrap

You are wiring the **classical GoF Observer pattern** in one service. The pattern carries over from M2 to M3 unchanged — `docs/m3/uber-m3.md:44` says "MongoDB event logging (Observer pattern stays in place)". Original spec authority: `Uber_descriptionM2.pdf` §3.3 (Observer), §3.7 (Factory), §4.5 (composition), §7.1 (event types).

The hard rule: **all MongoDB event writes must flow through this chain — no `@EventListener` may write to Mongo, and no class may construct events with `new <Event>(...)` outside the factory.** The grader source-scans both. Spring's `ApplicationEventPublisher` + `@EventListener` is fine for *non-Mongo* events (e.g., logging), but cannot be the path that persists Mongo documents.

## Coexistence with M3 RabbitMQ consumers (uber-m3.md:2645)

In M3 a single business write can produce **two** event surfaces:

1. **Observer → MongoDB** — the local-state audit log this skill wires (e.g., `RIDE_COMPLETED` → `ride_events`).
2. **RabbitMQ publisher → TopicExchange** — the cross-service async event for choreography saga participants (e.g., `ride.completed` on `ride.events`). Wired by `rabbitmq-bootstrap`, not this skill.

When the corresponding RabbitMQ consumer in another service receives the event and mutates local state, that consumer must be **state-guarded for idempotency** per Critical Rule #11 (uber-m3.md:2645): "Use **state-based idempotency** — check the target row's status before mutating." Otherwise an at-least-once retry doubles the write. Example: `ride.completed` consumer in driver-service that increments earnings must read the driver row first and skip if the rideId has already been counted.

This skill does not wire the RabbitMQ side. It only ensures the Observer chain is in place. The two layers stay independent.

## Sources of Truth (Read First)

1. **`docs/m3/event-actions.md`** — canonical action vocabularies + payment-shaped action rules + the no-`new <Event>(...)` and no-`@EventListener` rules + the new RabbitMQ routing-key column. **Read this before wiring any event.** The tables in this skill summarize it; the doc is canonical.
2. **`docs/m3/design-patterns.md`** — DP-2 Observer + DP-6 Factory grader hooks. Read the relevant sections.
3. **`Uber_descriptionM2.pdf` §3.3, §3.7, §4.5, §7.1** — original M2 spec text. Use `spec-clause-finder --milestone m2` if you need a verbatim clause.

If the doc and this skill disagree, trust the doc and flag the drift.

## Step 1: Identity + Branch

Confirm developer + ID. Pick a service (or run for "all 5", but each service is its own branch):

```
git checkout main && git pull origin main
git checkout -b chore/M3/cc/observer-<service>/<studentId>
```

## Step 2: EntityObserver interface (DP-2)

Define once, in a place every service can use (e.g., `com.team01.uber.<service>.event.EntityObserver`).

```java
public interface EntityObserver {
    void onEvent(String eventType, Object payload);
}
```

§3.3 hard requirements verified by reflection: interface must exist with that exact signature.

## Step 3: MongoEvent interface (DP-7 part 1)

```java
public interface MongoEvent {
    String getId();
    LocalDateTime getTimestamp();
    String getAction();
    Map<String, Object> getDetails();
}
```

Every concrete event class (`AuthEvent`, `DriverEvent`, `RideEvent`, `LocationEvent`, `PaymentAuditEvent`) implements this. Already created if `nosql-bootstrap` ran — confirm before continuing.

## Step 4: EventFactory (Factory — DP-6)

```java
public enum EventType { AUTH, DRIVER, RIDE, LOCATION, PAYMENT_AUDIT }

@Component
public class EventFactory {
    public MongoEvent createEvent(EventType type, Map<String, Object> params) {
        return switch (type) {
            case AUTH -> buildAuthEvent(params);
            case DRIVER -> buildDriverEvent(params);
            case RIDE -> buildRideEvent(params);
            case LOCATION -> buildLocationEvent(params);
            case PAYMENT_AUDIT -> buildPaymentAuditEvent(params);
        };
    }

    private AuthEvent buildAuthEvent(Map<String, Object> p) {
        AuthEvent e = new AuthEvent();
        e.setUserId((Long) p.get("userId"));
        e.setAction((String) p.get("action"));
        e.setTimestamp(LocalDateTime.now());
        e.setDetails(extractDetails(p));
        return e;
    }
    // ... and the others
}
```

§3.7 hard requirements: `createEvent(EventType, Map<String,Object>)` exists; PaymentAuditEvent factory path populates `method` and `amount` for payment-shaped actions; every concrete event is assignable to `MongoEvent`. Source-scan rule: nothing else may call `new AuthEvent(...)` etc.

## Step 5: MongoEventLogger (Observer concrete — bound to one EventType)

```java
@Component
public class MongoEventLogger implements EntityObserver {
    private final EventType boundType;
    private final EventFactory factory;
    private final MongoTemplate mongo;
    private static final Logger log = LoggerFactory.getLogger(MongoEventLogger.class);

    public MongoEventLogger(EventType boundType, EventFactory factory, MongoTemplate mongo) {
        this.boundType = boundType;
        this.factory = factory;
        this.mongo = mongo;
    }

    @Override
    public void onEvent(String actionString, Object payload) {
        try {
            Map<String, Object> params = new HashMap<>();
            if (payload instanceof Map<?, ?> m) {
                m.forEach((k, v) -> params.put(String.valueOf(k), v));
            }
            params.put("action", actionString);
            MongoEvent event = factory.createEvent(boundType, params);
            mongo.save(event);   // collection inferred from @Document on the class
        } catch (Exception ex) {
            // Soft dependency — never let Mongo failure roll back the upstream PG tx.
            log.warn("Mongo event log failed: {}", ex.getMessage());
        }
    }
}
```

§3.3 + §4.5 critical points:

- Each of the 5 services owns **its own** `MongoEventLogger` bound to a **fixed EventType** at construction (user-service binds AUTH, driver-service binds DRIVER, ride-service binds RIDE, location-service binds LOCATION, payment-service binds PAYMENT_AUDIT). Observer registration is NOT shared across services.
- The logger receives `(actionString, payload)`, copies the action string into `params.put("action", ...)`, then calls `EventFactory.createEvent(boundEventType, params)`. **The action string is NOT the EventType passed to the factory.**
- Failure policy: catch Mongo exception, `log.warn`, do not rethrow.

Wire the per-service binding in a `@Configuration`:

```java
@Configuration
public class ObserverConfig {
    @Bean
    public MongoEventLogger mongoEventLogger(EventFactory factory, MongoTemplate mongo) {
        return new MongoEventLogger(EventType.AUTH /* per service */, factory, mongo);
    }
}
```

## Step 6: Subject Mixin (so services can register/notify)

Two acceptable shapes. Pick one and stay consistent.

### Option A — abstract base class

```java
public abstract class ObservableSubject {
    private final List<EntityObserver> observers = new CopyOnWriteArrayList<>();
    public void register(EntityObserver o) { observers.add(o); }
    public void unregister(EntityObserver o) { observers.remove(o); }
    protected void notifyObservers(String action, Object payload) {
        for (EntityObserver o : observers) o.onEvent(action, payload);
    }
}
```

Services extend this in their service-layer classes.

### Option B — inject a `Subject` component

```java
@Component
public class ServiceSubject {
    private final List<EntityObserver> observers = new CopyOnWriteArrayList<>();
    public void register(EntityObserver o) { observers.add(o); }
    public void unregister(EntityObserver o) { observers.remove(o); }
    public void notifyObservers(String action, Object payload) {
        for (EntityObserver o : observers) o.onEvent(action, payload);
    }
}
```

Then on each service class: constructor-inject `ServiceSubject` + `MongoEventLogger`, register the logger in a `@PostConstruct`.

§3.3 hard requirement: the methods are `register`, `unregister`, `notifyObservers` — that exact set.

## Step 7: Wire One Demo Write End-to-End (template)

Pick the smallest M1 write in this service and route its Mongo log through the observer chain. Example for user-service S1-F2 (Update User Preferences):

```java
@Service
public class UserService extends ObservableSubject {
    private final UserRepository repo;
    private final MongoEventLogger logger;

    public UserService(UserRepository repo, MongoEventLogger logger) {
        this.repo = repo;
        this.logger = logger;
        register(logger);
    }

    public User updatePreferences(Long id, Map<String,Object> prefs) {
        User u = repo.findById(id).orElseThrow(/* 404 */);
        u.setPreferences(prefs);
        User saved = repo.save(u);

        // Observer notify — action string is what happened, NOT the EventType
        notifyObservers("USER_UPDATED", Map.of(
            "userId", saved.getId(),
            "details", Map.of("preferences", prefs)
        ));
        return saved;
    }
}
```

Then verify by hitting the endpoint and inspecting `auth_events`:

```
mongosh "mongodb://root:rootpass@localhost:27017/ubermongo?authSource=admin" \
  --eval 'db.auth_events.find({action:"USER_UPDATED"}).limit(1).pretty()'
```

## Step 8: Ban `new <Event>` Outside the Factory

Source-scan rule (grader runs this):

```
grep -rEn "new (AuthEvent|DriverEvent|RideEvent|LocationEvent|PaymentAuditEvent)\b" \
  --include='*.java' \
  <service>/src/main/java/ \
  | grep -v '/event/EventFactory.java'
```

The output must be empty. Fix any violation by routing it through the factory.

## Step 9: Ban `@EventListener` to Mongo

Source-scan rule:

```
grep -rEln "@EventListener" --include='*.java' <service>/src/main/java/ \
  | xargs -r grep -ln "MongoTemplate\|MongoRepository"
```

The output must be empty. Spring Events are fine for non-Mongo work; if you find a class that uses both `@EventListener` and writes to Mongo, refactor the Mongo write into a service that goes through `notifyObservers`.

## Step 10: Action Vocabulary Sanity

Per §7.1 the primary action values per service (UPPER_SNAKE_CASE; non-exhaustive):

- **auth_events**: REGISTERED, LOGGED_IN, ROLE_CHANGED, USER_UPDATED, USER_DEACTIVATED, DEFAULT_ADDRESS_SET, USER_CREATED, USER_DELETED.
- **driver_events**: INDEXED, UPDATED, DASHBOARD_VIEWED, VEHICLE_DETAILS_UPDATED, AVAILABILITY_UPDATED, RATING_RECORDED, DOCUMENT_VERIFIED, DRIVER_CREATED, DRIVER_DELETED.
- **ride_events**: ANALYTICS_VIEWED, INTERACTION_RECORDED, DRIVER_ASSIGNED, RIDE_COMPLETED, RIDE_CANCELLED, STOPS_ADDED, RIDE_CREATED, RIDE_DELETED.
- **location_events**: TRACKING_RECORDED, ANALYTICS_VIEWED, LOCATION_UPDATED, BATCH_LOCATIONS_UPDATED, OLD_LOCATIONS_PURGED, LOCATION_DELETED.
- **payment_audit_trail**: CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, ANALYTICS_VIEWED, COUPON_APPLIED, RETRY_ATTEMPTED, PAYMENT_DELETED. Payment-shaped actions (CREATED, COMPLETED, FAILED, REFUNDED, REFUND_DENIED, RETRY_ATTEMPTED) **must** carry `method` and `amount`.

When wiring a write, double-check the action string is in this vocabulary or is a clear UPPER_SNAKE_CASE extension.

## Step 11: Test Plan

- Hit the demo write → matching Mongo collection has a document with the expected `action`.
- Unregister the logger in a unit test and re-trigger the write → Mongo collection gets NO new document (proves logging path is via observer chain, not direct Mongo call).
- 10 parallel writes → 10 documents (CopyOnWriteArrayList is thread-safe; verify no exceptions).
- Stop the Mongo container, trigger the write → service still returns 200 (PG tx commits), `log.warn` line shows up. Restart Mongo.
- Static check: `grep -rEn "new (AuthEvent|...)" ...` excluding factory → empty.
- Static check: `@EventListener` writing to Mongo → empty.

## Step 12: Hand Off

Once one demo write is wired, propose to the user: "Should I also retrofit the other M1 writes in this service (S<n>-F2/F4/F7 + CRUD writes)?" Each should follow the same `notifyObservers(action, payload)` pattern. Land them as separate small commits within the same branch (or a follow-up `feat/m1/MOD-5a-<service>` branch — see `m1-retrofit-runner`).
