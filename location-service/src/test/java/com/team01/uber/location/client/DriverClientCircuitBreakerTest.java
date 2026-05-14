package com.team01.uber.location.client;

import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.feign.DriverServiceClient;
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
class DriverClientCircuitBreakerTest {

    @Mock
    private DriverServiceClient feignClient;

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
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("driver-service");
    }

    private DriverDTO callThroughCb(long driverId) {
        return circuitBreaker.executeSupplier(() -> feignClient.getDriver(driverId));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        FeignException error = new FeignException.ServiceUnavailable(
                "driver-service down",
                Request.create(Request.HttpMethod.GET, "/api/drivers/1", Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of());

        when(feignClient.getDriver(anyLong())).thenThrow(error);

        // Phase 1: fire 5 calls — all fail
        for (int i = 0; i < 5; i++) {
            assertThrows(FeignException.class, () -> callThroughCb(1L));
        }

        // Circuit must now be OPEN (100% failure rate > 50% threshold)
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Phase 2: next call is rejected immediately without hitting Feign at all
        assertThrows(CallNotPermittedException.class, () -> callThroughCb(1L));
        // Feign was called exactly 5 times, not 6
        verify(feignClient, times(5)).getDriver(anyLong());
    }

    @Test
    void fallbackReturnsUnavailableDriver() {
        FeignException error = new FeignException.ServiceUnavailable(
                "driver-service down",
                Request.create(Request.HttpMethod.GET, "/api/drivers/1", Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of());

        when(feignClient.getDriver(anyLong())).thenThrow(error);

        // Force circuit OPEN
        for (int i = 0; i < 5; i++) {
            try { callThroughCb(1L); } catch (Exception ignored) {}
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Simulate what DriverClient.getDriverFallback() returns
        DriverClient client = new DriverClient(feignClient);
        DriverDTO fallback = client.getDriverFallback(1L, new RuntimeException("circuit open"));

        assertEquals("UNAVAILABLE", fallback.status());
        assertEquals(1L, fallback.id());
        assertEquals("Unknown", fallback.name());
    }

    @Test
    void circuitClosesAfterSuccessfulRecovery() throws InterruptedException {
        FeignException error = new FeignException.ServiceUnavailable(
                "driver-service down",
                Request.create(Request.HttpMethod.GET, "/api/drivers/1", Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of());

        // Phase 1: open the circuit
        when(feignClient.getDriver(anyLong())).thenThrow(error);
        for (int i = 0; i < 5; i++) {
            try { callThroughCb(1L); } catch (Exception ignored) {}
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Phase 2: transition to HALF_OPEN manually (avoids sleeping 10s in tests)
        circuitBreaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        // Phase 3: driver-service recovers — reset mock and return success
        DriverDTO recovered = new DriverDTO(1L, "Driver A", "AVAILABLE", Map.of());
        reset(feignClient);
        when(feignClient.getDriver(anyLong())).thenReturn(recovered);

        callThroughCb(1L);
        callThroughCb(1L);

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }
}
