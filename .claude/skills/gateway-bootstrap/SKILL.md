---
name: gateway-bootstrap
description: Wire the api-gateway 7th Maven module per uber-m3.md §9 — Spring Cloud Gateway reactive WebFlux, JwtGatewayFilter as a GlobalFilter (NOT OncePerRequestFilter), 5 route predicates, /api/auth/** bypass, X-User-Id/X-User-Role/X-Correlation-ID forwarding, NodePort 30080. Critical Rule #8 — "JWT validation at gateway" (uber-m3.md:2642). Replaces jwt-bootstrap as the primary validator; service-side filters stay as defense-in-depth.
---

# API Gateway Bootstrap

You are wiring the 6th-functional Maven module (the 7th counting `contracts/`) — `api-gateway` — that becomes the public-facing JWT validator and the **only** ingress to the cluster. Per Critical Rule #8 (uber-m3.md:2642):

> **JWT validation at gateway.** Individual services retain their M2 JWT filter for defense-in-depth, but the gateway is the public-facing validator.

The M2 service-side `JwtAuthenticationFilter` is unchanged and still wired. This skill builds the gateway in addition.

## Sources of Truth (Read First)

1. **`docs/m3/jwt-contract.md`** — the gateway-vs-service split + reactive filter checklist.
2. **`docs/m3/uber-m3.md` §9.1–§9.4** — original spec (lines 1401–1499).
3. **`docs/m3/k8s-manifests.md` §10.6** — gateway Deployment + NodePort Service.

## Spec Lookup — Always Ask First

Before dispatching `spec-clause-finder` for verbatim spec text mid-run, **always** use `AskUserQuestion` to offer the user the cheaper companion-doc path first. Companion-doc reads (`docs/m3/jwt-contract.md`, `docs/m3/k8s-manifests.md` here) are ~10× cheaper than spawning the agent. Escalate to `spec-clause-finder` only when (a) the relevant `docs/m3/*.md` looks ambiguous or contradicts the spec, (b) you need surrounding spec context the digest doesn't carry, or (c) the user explicitly asks for verbatim text. **Never silently escalate.** Full rule in `.claude/CLAUDE.md`.

## Step 1: Identity + Branch

```
git checkout main && git pull origin main
git checkout -b chore/M3/cc/gateway/<studentId>
```

## Step 2: Add the module to root pom (uber-m3.md:1405–1417)

```xml
<modules>
    <module>contracts</module>
    <module>user-service</module>
    <module>driver-service</module>
    <module>ride-service</module>
    <module>location-service</module>
    <module>payment-service</module>
    <module>api-gateway</module>
</modules>
```

## Step 3: Gateway pom (uber-m3.md:1421–1432)

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

> Spring Cloud Gateway is **reactive** (Project Reactor). Do NOT add `spring-boot-starter-web` — it conflicts with webflux. The artifact is `spring-cloud-starter-gateway-server-webflux` (the renamed gateway starter introduced in Spring Cloud `2025.1.x`); the old `spring-cloud-starter-gateway` artifact name is from the `2025.0.x` release train and does not resolve under the BOM declared in §2.1. (uber-m3.md:1434)

## Step 4: 5 route predicates in `application.yml` (uber-m3.md:1438–1465)

```yaml
spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: http://user-service:8080
          predicates:
            - Path=/api/users/**, /api/auth/**
        - id: driver-service
          uri: http://driver-service:8080
          predicates:
            - Path=/api/drivers/**
        - id: ride-service
          uri: http://ride-service:8080
          predicates:
            - Path=/api/rides/**
        - id: location-service
          uri: http://location-service:8080
          predicates:
            - Path=/api/locations/**
        - id: payment-service
          uri: http://payment-service:8080
          predicates:
            - Path=/api/payments/**
```

## Step 5: `JwtGatewayFilter` — reactive `GlobalFilter` (uber-m3.md:1469–1487)

The M2 servlet filter cannot be copy-pasted. Five concrete differences:

1. **Class shape.** Implement `org.springframework.cloud.gateway.filter.GlobalFilter` (NOT `OncePerRequestFilter`). Signature:
   ```java
   Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain)
   ```
   No `HttpServletRequest`/`HttpServletResponse`. No `void` return. No `chain.doFilter(req, res)`.
2. **Filter ordering.** Annotate `@Component` AND implement `Ordered` returning `-1` (or `@Order(-1)`) so the filter executes **before** Spring Cloud Gateway's route-resolution filter.
3. **Path bypass.** Read path via `exchange.getRequest().getPath().value()`. Bypass `/api/auth/**`. All other paths require a valid `Authorization: Bearer <token>`.
4. **Header parsing & validation.** Read `Authorization` from `exchange.getRequest().getHeaders().getFirst("Authorization")`, strip `Bearer ` prefix, validate against env-injected `JWT_SECRET` using JJWT (lifted from M2's `AuthenticationService`). On failure: `exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED)` and `return exchange.getResponse().setComplete()` (terminal `Mono<Void>`).
5. **Header forwarding.** On success, mutate the downstream request:
   ```java
   ServerHttpRequest mutated = exchange.getRequest().mutate()
       .header("X-User-Id", claims.get("uid", Long.class).toString())
       .header("X-User-Role", claims.get("role", String.class))
       .header("X-Correlation-ID", correlationId)
       .build();
   return chain.filter(exchange.mutate().request(mutated).build());
   ```

The token-validation logic from M2's `AuthenticationService` (HMAC verification, claim extraction, expiry check) goes into a new self-contained class at `api-gateway/src/main/java/.../gateway/auth/JwtValidator.java`. The api-gateway does **not** depend on `contracts/` and does **not** call any other service via Feign (uber-m3.md:1489).

## Step 6: Correlation ID generation

If the inbound request lacks `X-Correlation-ID`, the filter generates a UUID and sets it as both the inbound MDC value AND the outbound header to downstream services. Downstream services pick it up via the `OncePerRequestFilter` from `observability-bootstrap`.

## Step 7: K8s — Deployment + NodePort Service (uber-m3.md:1707–1727)

`k8s/api-gateway/gateway-deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
  namespace: uber
spec:
  replicas: 1
  selector:
    matchLabels:
      app: api-gateway
  template:
    metadata:
      labels:
        app: api-gateway
    spec:
      containers:
        - name: api-gateway
          image: <your-registry>/api-gateway:latest
          ports:
            - containerPort: 8080
          env:
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: jwt-secret
                  key: jwt-secret
```

`k8s/api-gateway/gateway-service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api-gateway
  namespace: uber
spec:
  type: NodePort
  selector:
    app: api-gateway
  ports:
    - port: 8080
      targetPort: 8080
      nodePort: 30080
```

Access: `curl http://$(minikube ip):30080/api/...` (uber-m3.md:1725).

## Step 8: Verify mode

- `api-gateway` module in root `pom.xml`.
- pom has `spring-cloud-starter-gateway-server-webflux` + `spring-boot-starter-webflux`. **No `spring-boot-starter-web`** (uber-m3.md:1434).
- `application.yml` has all 5 route entries with `Path=/api/<svc>/**`.
- `JwtGatewayFilter` implements `GlobalFilter`, not `OncePerRequestFilter`.
- `Ordered.getOrder() == -1` (or `@Order(-1)`).
- `/api/auth/**` bypass exists.
- Header forwarding writes `X-User-Id`, `X-User-Role`, `X-Correlation-ID`.
- K8s Service is `type: NodePort, nodePort: 30080`.

## Constraints

- **Never reveal AI authorship.**
- **Never push, merge, or open a PR directly.**
- This skill **does not** remove the M2 service-side `JwtAuthenticationFilter` — those stay as defense-in-depth.
