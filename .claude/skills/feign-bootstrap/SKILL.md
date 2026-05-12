---
name: feign-bootstrap
description: Wire OpenFeign clients per uber-m3.md §2.1-§2.4 — pom dep + Spring Cloud BOM 2025.1.1 + @EnableFeignClients + per-service @FeignClient interfaces in the contracts/ Maven module + correlation-ID interceptor + try-catch error handling + N+1 candidate-set caps. Critical Rule #2 — "Feign for reads. RabbitMQ for side-effects" (uber-m3.md:2636).
---

# Feign Bootstrap

You are wiring OpenFeign as the synchronous read channel between services. Cross-service reads are Feign; cross-service writes are RabbitMQ (`rabbitmq-bootstrap`). The two are non-overlapping.

## Sources of Truth (Read First)

1. **`docs/m3/feign-contracts.md`** — full Feign topology, contract module placement, error handling, correlation propagation, N+1 cap.
2. **`docs/m3/uber-m3.md` §2.1–§2.4** — original spec text (lines 137–241).
3. **`docs/m3/yaml-fragments/<service>.application.yml`** — `feign:` block per service.
4. Saga pre-checks: `docs/m3/saga-events.md` (uber-m3.md:1225–1228) — the three S3-F4 Feign calls.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/feign-contracts.md`, `docs/m3/saga-events.md` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Step 1: Identity + Branch

```
git checkout main && git pull origin main
git checkout -b chore/M3/<scope>/feign-<service>/<studentId>
```

## Step 2: pom.xml + BOM (uber-m3.md:139–164)

Add to the service's `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

Confirm Spring Cloud BOM in root `pom.xml`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>2025.1.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Step 3: `@EnableFeignClients` on the application class (uber-m3.md:166–172)

```java
@SpringBootApplication
@EnableFeignClients
public class <Service>Application { }
```

## Step 4: Declare interfaces in `contracts/`, NOT in the service (uber-m3.md:2570)

> Every `@FeignClient` interface signature ... Committed once to a `contracts/` Maven module that all services depend on.

Create the interface under `contracts/src/main/java/.../feign/<Other>ServiceClient.java`. Pattern (uber-m3.md:174–189):

```java
@FeignClient(name = "ride-service", url = "${feign.ride-service.url}")
public interface RideServiceClient {
    @GetMapping("/api/rides/user/{userId}/summary")
    RideSummaryDTO getUserRideSummary(@PathVariable Long userId);
    ...
}
```

DTOs returned by Feign clients live in `contracts/src/main/java/.../dto/`.

## Step 5: `feign:` block in `application.yml` (uber-m3.md:193–205)

Reference `docs/m3/yaml-fragments/<service>.application.yml`. Pattern:

```yaml
feign:
  <other-service>:
    url: ${FEIGN_<OTHER>_SERVICE_URL:http://<other-service>:8080}
```

In K8s, `FEIGN_<OTHER>_SERVICE_URL` is set on the service's ConfigMap (uber-m3.md:1600–1603).

## Step 6: Correlation-ID interceptor (uber-m3.md:208–225)

Verbatim:

```java
@Configuration
public class FeignCorrelationConfig {
    @Bean
    public RequestInterceptor correlationIdInterceptor() {
        return template -> {
            String correlationId = MDC.get("correlationId");
            if (correlationId != null) {
                template.header("X-Correlation-ID", correlationId);
            }
        };
    }
}
```

The MDC value comes from the inbound `X-Correlation-ID` header set by api-gateway (uber-m3.md:1855) or from the RabbitMQ message header in consumers.

## Step 7: Error handling (uber-m3.md:227–241)

> Wrap every Feign call in try-catch. Never let a downstream failure crash the calling service.

Pattern:

```java
try {
    RideSummaryDTO summary = rideServiceClient.getUserRideSummary(userId);
    return buildDTO(user, summary);
} catch (FeignException.NotFound e) {
    return buildDTO(user, RideSummaryDTO.empty());
} catch (FeignException e) {
    log.warn("ride-service unavailable for user {}: {}", userId, e.getMessage());
    throw new ServiceUnavailableException("Ride service temporarily unavailable");
}
```

## Step 8: Saga pre-check Feign calls (uber-m3.md:1225–1228)

For ride-service S3-F4 only — the saga trigger fans out three calls:

| Endpoint | Expected | On failure |
|---|---|---|
| `GET /api/users/{id}` | status = `ACTIVE` | 404 / `DEACTIVATED` → 400 |
| `GET /api/drivers/{id}` | status = `BUSY` | 404 / other status → 400 |
| `GET /api/locations/driver/{driverId}/recent` | 200 with ping ≤ 5 min old | 404 → 400 |

All three must pass before any event is published. See `docs/m3/saga-events.md`.

## Step 9: N+1 candidate-set cap (uber-m3.md:380)

> Every M3 feature that does per-element Feign calls **must** cap the candidate set to at most **100 elements** at the local-DB query stage (`LIMIT 100`).

Features hit: S1-F6, S1-F9, S3-F12, S4-F3, S4-F9, S5-F10. Add `LIMIT 100` to the local query that produces the candidate IDs.

## Step 10: Verify mode

- pom dep present.
- `@EnableFeignClients` on application class.
- All `@FeignClient` interfaces under `contracts/`, none under the service's own `src/main/java/`.
- `feign:` block in `application.yml` with one entry per Feign client used.
- `FeignCorrelationConfig` present with the correlation interceptor.
- Every Feign call site wraps in try-catch.
- N+1 features have `LIMIT 100` on their candidate query.

## Constraints

- **Never reveal AI authorship.**
- **Never push, merge, or open a PR directly.**
- This skill assumes `db-isolation-bootstrap` has already run (or will). The cross-service queries it removes get replaced by the Feign clients this skill wires.
