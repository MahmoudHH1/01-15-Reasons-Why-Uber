package com.team01.uber.ride.client;

import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.contracts.feign.UserServiceClient;
import feign.FeignException;
import feign.Request;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserClientCircuitBreakerTest {

    @Mock
    private UserServiceClient feignClient;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("user-service");
    }

    private UserDTO callThroughCb(long userId) {
        return circuitBreaker.executeSupplier(() -> feignClient.getUser(userId));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        FeignException error = new FeignException.ServiceUnavailable(
                "user-service down",
                Request.create(Request.HttpMethod.GET, "/api/users/1", Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of());

        when(feignClient.getUser(anyLong())).thenThrow(error);

        // Phase 1: fire 5 calls — all fail
        for (int i = 0; i < 5; i++) {
            assertThrows(FeignException.class, () -> callThroughCb(1L));
        }

        // Circuit must now be OPEN
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Phase 2: next call rejected immediately
        assertThrows(CallNotPermittedException.class, () -> callThroughCb(1L));
        verify(feignClient, times(5)).getUser(anyLong());
    }

    @Test
    void fallbackReturnsUnknownUser() {
        UserClient client = new UserClient(feignClient);
        UserDTO fallback = client.getUserFallback(1L, new RuntimeException("circuit open"));

        assertEquals("Unknown", fallback.name());
        assertEquals("ACTIVE", fallback.status());
        assertEquals(1L, fallback.id());
    }
}
