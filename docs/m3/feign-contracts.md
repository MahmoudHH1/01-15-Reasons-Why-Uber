<!-- Loaded by: skills/feign-bootstrap, skills/m3-orchestrator, skills/pr-check -->

# M3 Feign Contracts

Source: `docs/m3/uber-m3.md` §2.1–§2.4, §2.10, §2.12, §13.2.

## Why Feign

> **Feign for reads. RabbitMQ for side-effects.** Use Feign when you need data to continue processing. Use RabbitMQ when triggering a state change in another service. (uber-m3.md:2636)

## Module placement (uber-m3.md:2570)

> Every `@FeignClient` interface signature (e.g., `RideServiceClient.getUserRideSummary`) and the DTOs they return (`RideSummaryDTO`, `DriverRideSummaryDTO`, etc.). **Committed once to a `contracts/` Maven module that all services depend on.**

The `contracts/` module is the only Day-0 dependency (uber-m3.md:2566). All services build against it; no service writes its own copy.

## Bootstrap (uber-m3.md:139–172)

### pom.xml

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

### Spring Cloud BOM

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

### Enable

```java
@SpringBootApplication
@EnableFeignClients
public class UserServiceApplication { }
```

### Client interface pattern (uber-m3.md:174–189)

```java
@FeignClient(name = "ride-service", url = "${feign.ride-service.url}")
public interface RideServiceClient {

    @GetMapping("/api/rides/user/{userId}/summary")
    RideSummaryDTO getUserRideSummary(@PathVariable Long userId);

    @GetMapping("/api/rides/user/{userId}/active-count")
    int getActiveRideCount(@PathVariable Long userId);

    @GetMapping("/api/rides/user/{userId}/completed-count")
    long getCompletedRideCount(@PathVariable Long userId);
}
```

### URL config in `application.yml` (uber-m3.md:193–205)

```yaml
feign:
  user-service:
    url: http://user-service:8080
  driver-service:
    url: http://driver-service:8080
  ride-service:
    url: http://ride-service:8080
  location-service:
    url: http://location-service:8080
  payment-service:
    url: http://payment-service:8080
```

In K8s, these come from per-service ConfigMaps as `FEIGN_<SERVICE>_URL` env vars (uber-m3.md:1600–1603), and the `application.yml` reads them via `${FEIGN_USER_SERVICE_URL:http://user-service:8080}` style placeholder.

## Correlation ID propagation (uber-m3.md:208–225)

Every service forwards `X-Correlation-ID` on outgoing Feign calls:

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

The MDC value comes from the inbound `X-Correlation-ID` header (set by api-gateway on every external request — see `docs/m3/observability.md`).

## Error handling (uber-m3.md:227–241)

Wrap every Feign call in try-catch. **Never let a downstream failure crash the calling service.**

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

## Saga pre-check Feign calls (uber-m3.md:1225–1228)

S3-F4 (the saga trigger) makes three Feign calls before publishing `ride.completed`. All three are required:

| From → To | Endpoint | Expected | On failure |
|---|---|---|---|
| ride → user | `GET /api/users/{id}` | status = `ACTIVE` | 404 or `DEACTIVATED` → 400 |
| ride → driver | `GET /api/drivers/{id}` | status = `BUSY` | 404 or any other status → 400 |
| ride → location | `GET /api/locations/driver/{driverId}/recent` | 200 with ping ≤ 5 min old | 404 → 400 ("driver not actively tracked") |

## N+1 fan-out cap (uber-m3.md:376–382)

> Every M3 feature that does per-element Feign calls **must** cap the candidate set to at most **100 elements** at the local-DB query stage (`LIMIT 100`). Beyond 100, return what fits and document the truncation.

Features that hit this hazard: S1-F6 (per-user payment totals), S1-F9 (per-user completed-count), S3-F12 (per-driver enrichment for recommendations), S4-F3 (per-driver status filter for nearby search), S4-F9 (per-driver name enrichment for stationary), S5-F10 (per-driver vehicleType for surge-fee categorization).

## Authorization convention (uber-m3.md:339–356)

Path-param endpoints require ownership-or-admin. Per uber-m3.md:341:

> The path-param `{userId}` or `{driverId}` must equal the caller's `X-User-Id`, **OR** the caller's `X-User-Role` must equal `ADMIN`. Otherwise → throw **403 Forbidden** ("not authorized to access this resource").

> **Caller-existence rule:** Before any business logic runs, look up the caller (via local query or Feign to user-service for cross-service paths) — if not found, → throw **404 Not Found** ("caller user not found").

Endpoints affected (uber-m3.md:347–354):

| Endpoint | Service | Ownership check |
|---|---|---|
| `GET /api/users/{id}/ride-summary` (S1-F3) | S1 | `id == X-User-Id` OR caller is ADMIN |
| `PUT /api/users/{id}/deactivate` (S1-F4) | S1 | `id == X-User-Id` OR caller is ADMIN |
| `GET /api/drivers/{id}/earnings` (S2-F3) | S2 | driver `{id}` == caller's driver-user-id, OR caller is ADMIN |
| `GET /api/drivers/{id}/dashboard` (S2-F12) | S2 | same |
| `GET /api/rides/recommendations?userId=` (S3-F12) | S3 | `userId == X-User-Id` OR caller is ADMIN |
| `GET /api/payments/user/{id}/summary` (S5-F3) | S5 | `id == X-User-Id` OR caller is ADMIN |

Aggregate report endpoints (S1-F6 top-riders, S2-F6 top-rated-drivers, S5-F1 revenue) require **ADMIN only**.

## Day-0 contracts (uber-m3.md:2568–2574)

Committed up-front by the team lead so all 15 slices proceed in parallel:

- **Feign client interfaces** in `contracts/.../feign/`: every `@FeignClient` interface signature, plus the DTOs they return.
- **Event payload records** in `contracts/.../events/`: every `record` class.
- **Routing keys + exchange names** are fixed in §2.9 (no team debate).
- **New endpoint paths + DTO shapes** — exact path, query params, response JSON. Already documented in each service's "New Endpoints" tables (§3–§7).
- **K8s Service names** — `loki`, `prometheus`, `rabbitmq`, `<svc>-postgres` — fixed up-front so DNS resolves correctly across slices.
- **Shared YAML stub files** — `api-gateway/application.yml` (with route placeholders), `prometheus-configmap.yaml` (with scrape-job placeholders), `grafana-dashboards.yaml` ConfigMap (referencing 5 dashboard JSON paths).
