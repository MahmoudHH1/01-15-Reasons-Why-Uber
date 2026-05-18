package com.team01.uber.gateway.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

@Component
public class JwtGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtGatewayFilter.class);
    private final JwtValidator jwtValidator;

    public JwtGatewayFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        String correlationId = exchange.getRequest().getHeaders()
                .getFirst("X-Correlation-ID");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        if (isPublicPath(path)) {
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-Correlation-ID", correlationId)
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        String authHeader = exchange.getRequest().getHeaders()
                .getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or malformed Authorization header for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtValidator.validateAndExtract(token);

            String userId = claims.get("uid", Long.class).toString();
            String userRole = claims.get("role", String.class);

            String finalCorrelationId = correlationId;
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Role", userRole)
                    .header("X-Correlation-ID", finalCorrelationId)
                    .build();

            log.info("Authenticated user {} with role {} for path {}",
                    userId, userRole, path);

            return chain.filter(exchange.mutate().request(mutated).build());

        } catch (JwtException e) {
            log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    private static final Set<String> HEALTH_PATHS = Set.of(
            "/api/users/health",
            "/api/drivers/health",
            "/api/rides/health",
            "/api/locations/health",
            "/api/payments/health");

    private boolean isPublicPath(String path) {
        return path.startsWith("/api/auth/")
                || path.startsWith("/actuator/")
                || HEALTH_PATHS.contains(path);
    }
}