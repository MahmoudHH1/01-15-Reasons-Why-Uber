---
name: rabbitmq-bootstrap
description: Wire RabbitMQ topology per uber-m3.md §2.5-§2.9 — spring-boot-starter-amqp dep + spring.rabbitmq config (auto ACK, default-requeue-rejected: false, max-attempts: 3) + per-service TopicExchange beans + consumer queues with x-dead-letter-exchange (DLQ for every queue) + state-guarded idempotent consumers. Critical Rules #2-#4 + #11 (uber-m3.md:2636-2638, 2645). Publish-after-commit semantics, no outbox.
---

# RabbitMQ Bootstrap

You are wiring the **asynchronous event channel** between services. Cross-service reads are Feign; cross-service writes are RabbitMQ. Each service publishes to its own `<svc>.events` TopicExchange and consumes from queues bound to other services' exchanges.

## Critical Rules anchored

- **#3 Auto ACK with DLQ routing** — `acknowledge-mode: auto`, `default-requeue-rejected: false`, retry → DLQ via `x-dead-letter-exchange` (uber-m3.md:2637).
- **#4 DLQ for every queue** — failed messages never silently dropped (uber-m3.md:2638).
- **#11 Consumers must be idempotent** — state-based: read target row's status before mutating (uber-m3.md:2645).
- **§2.11 Publish-after-commit** — local PG transaction commits first, **then** RabbitMQ publish (uber-m3.md:360–362). No outbox in baseline scope.

## Sources of Truth (Read First)

1. **`docs/m3/event-actions.md`** — RabbitMQ Routing Keys table (the §2.9 verbatim).
2. **`docs/m3/saga-events.md`** — saga participant publish/consume matrix.
3. **`docs/m3/uber-m3.md` §2.5–§2.11** — original spec.
4. **`docs/m3/yaml-fragments/<service>.application.yml`** — `spring.rabbitmq.*` block.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/event-actions.md`, `docs/m3/saga-events.md` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Step 1: Identity + Branch

```
git checkout main && git pull origin main
git checkout -b chore/M3/<scope>/rabbitmq-<service>/<studentId>
```

## Step 2: pom.xml (uber-m3.md:249–253)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
```

## Step 3: `application.yml` block (uber-m3.md:262–275)

```yaml
spring:
  rabbitmq:
    host: ${SPRING_RABBITMQ_HOST:rabbitmq}
    port: 5672
    username: ${SPRING_RABBITMQ_USERNAME:guest}
    password: ${SPRING_RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        acknowledge-mode: auto
        default-requeue-rejected: false
        retry:
          enabled: true
          initial-interval: 1000
          max-attempts: 3
```

The `auto` + `default-requeue-rejected: false` + `max-attempts: 3` triple is what routes failed deliveries to the DLQ via the queue's `x-dead-letter-exchange` argument. **No manual basicAck/basicNack** in consumer code.

## Step 4: TopicExchange + queue + DLQ topology (uber-m3.md:277–286)

For each service create `<svc>EventConfig.java` with:

- One `TopicExchange` bean named per the §2.9 table (`user.events`, `driver.events`, `ride.events`, `location.events`, `payment.events`).
- For every queue this service consumes from: a `Queue` bean **with** `x-dead-letter-exchange` and `x-dead-letter-routing-key` arguments pointing at a separate dead-letter exchange + DLQ. Plus a `Binding` from the source exchange.
- A separate dead-letter `TopicExchange` and one `Queue` per DLQ.

Example shape (S2 — driver-service consuming `ride.placed`):

```java
@Bean
public TopicExchange driverEventsExchange() { return new TopicExchange("driver.events"); }

@Bean
public Queue driverRidePlacedQueue() {
    return QueueBuilder.durable("driver.ride.placed")
        .withArgument("x-dead-letter-exchange", "driver.events.dlx")
        .withArgument("x-dead-letter-routing-key", "driver.ride.placed.dlq")
        .build();
}

@Bean public TopicExchange driverEventsDlx() { return new TopicExchange("driver.events.dlx"); }
@Bean public Queue driverRidePlacedDlq() { return new Queue("driver.ride.placed.dlq"); }

@Bean
public Binding driverRidePlacedBinding(@Qualifier("rideEventsExchange") TopicExchange rideEvents) {
    return BindingBuilder.bind(driverRidePlacedQueue()).to(rideEvents).with("ride.placed");
}
```

## Step 5: Event payload records in `contracts/` (uber-m3.md:288–315)

Every event payload is a Java `record` in `contracts/.../events/`. Reference list at uber-m3.md:294–315.

## Step 6: Publishers — commit-then-publish (uber-m3.md:360–362)

> Every publisher in this spec follows the same rule: **commit the local PostgreSQL transaction first, then publish to RabbitMQ**.

Pattern:

```java
@Transactional
public Ride completeRide(Long rideId) {
    Ride ride = rideRepo.findById(rideId).orElseThrow(...);
    // ...validation, status change, save...
    rideRepo.save(ride);  // local PG commits at end of @Transactional
    // (returning here finalizes the transaction)
    return ride;
}

// Caller publishes AFTER the transactional method returns:
public Ride completeRideAndPublish(Long rideId) {
    Ride ride = completeRide(rideId);
    rabbitTemplate.convertAndSend("ride.events", "ride.completed",
        new RideCompletedEvent(ride.getId(), ride.getUserId(), ride.getDriverId(), ride.getFare()));
    return ride;
}
```

The publish is **outside** the `@Transactional`. A failed publish does not roll back the local commit (acceptable per §2.11).

## Step 7: Consumers — state-guarded idempotency (uber-m3.md:2645)

Every `@RabbitListener` method must read the target row's status before mutating. Pattern (driver-service `ride.completed` consumer that flips driver to AVAILABLE + bumps earnings):

```java
@RabbitListener(queues = "driver.ride.completed")
@Transactional
public void onRideCompleted(RideCompletedEvent event) {
    Driver driver = driverRepo.findById(event.driverId()).orElseThrow();
    if (driver.getStatus() == DriverStatus.AVAILABLE) return;  // idempotency guard
    if (driver.getCountedRideIds().contains(event.rideId())) return;  // already processed

    driver.setStatus(DriverStatus.AVAILABLE);
    driver.setTotalEarnings(driver.getTotalEarnings() + event.fare());
    driver.getCountedRideIds().add(event.rideId());
    driverRepo.save(driver);
}
```

The exact guard varies; the pattern is the same: **check before mutate**.

## Step 8: Per-service exchange + queue inventory

| Service | Publishes (exchange / routing keys) | Consumes (queues) |
|---|---|---|
| user-service | `user.events` / `user.registered`, `user.deactivated` | `user.ride.completed`, `user.ride.cancelled` |
| driver-service | `driver.events` / `driver.status-changed`, `driver.rated`, `driver.document.verified` | `driver.ride.placed`, `driver.ride.completed`, `driver.ride.cancelled` |
| ride-service | `ride.events` / `ride.placed`, `ride.completed`, `ride.cancelled` | `ride.payment.initiated`, `ride.payment.completed`, `ride.payment.failed`, `ride.payment.refunded` |
| location-service | `location.events` / `location.tracked` (audit only, optional) | `location.ride.placed`, `location.ride.completed`, `location.ride.cancelled` |
| payment-service | `payment.events` / `payment.initiated`, `payment.completed`, `payment.failed`, `payment.refunded` | `payment.ride.completed`, `payment.ride.cancelled` |

Every queue listed above needs its own DLQ.

## Step 9: Verify mode

- pom dep present.
- `application.yml` `spring.rabbitmq.*` block matches the §2.6 config exactly (auto ACK, no requeue-rejected, retry max 3).
- One `<svc>EventConfig.java` per service with TopicExchange + queues + DLQs + bindings.
- Every consumer queue has `x-dead-letter-exchange` argument.
- Source-scan: every `@RabbitListener` method state-guards before mutating (read before write).
- Source-scan: no `rabbitTemplate.convertAndSend` inside a `@Transactional` method (publish-after-commit).
- Routing keys match the §2.9 table; no made-up keys.

## Constraints

- **Never reveal AI authorship.**
- **Never push, merge, or open a PR directly.**
- This skill writes the topology; it does NOT replace the Observer chain (`observer-bootstrap`) — Observer keeps writing local Mongo audit events independently.
