package com.team01.uber.user.client;

import com.team01.uber.contracts.dto.RideSummaryDTO;
import com.team01.uber.contracts.feign.RideServiceClient;
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
class RideClientCircuitBreakerTest {

    @Mock
    private RideServiceClient feignClient;

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
        circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("ride-service");
    }

    private RideSummaryDTO callThroughCb(long userId) {
        return circuitBreaker.executeSupplier(() -> feignClient.getUserRideSummary(userId));
    }

    @Test
    void circuitOpensAfterRepeatedFailures() {
        FeignException error = new FeignException.ServiceUnavailable(
                "ride-service down",
                Request.create(Request.HttpMethod.GET, "/api/rides/user/1/summary", Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of());

        when(feignClient.getUserRideSummary(anyLong())).thenThrow(error);

        // Phase 1: fire 5 calls — all fail
        for (int i = 0; i < 5; i++) {
            assertThrows(FeignException.class, () -> callThroughCb(1L));
        }

        // Circuit must now be OPEN (100% failure rate > 50% threshold)
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Phase 2: next call is rejected immediately without hitting Feign at all
        assertThrows(CallNotPermittedException.class, () -> callThroughCb(1L));
        // Feign was called exactly 5 times, not 6
        verify(feignClient, times(5)).getUserRideSummary(anyLong());
    }

    @Test
    void fallbackReturnsEmptySummary() {
        RideClient client = new RideClient(feignClient);
        RideSummaryDTO fallback = client.getUserRideSummaryFallback(1L, new RuntimeException("circuit open"));

        assertEquals(0L, fallback.totalRides());
        assertEquals(0L, fallback.completedRides());
        assertEquals(0.0, fallback.totalSpent());
        assertEquals(1L, fallback.userId());
    }

    @Test
    void circuitClosesAfterSuccessfulRecovery() {
        FeignException error = new FeignException.ServiceUnavailable(
                "ride-service down",
                Request.create(Request.HttpMethod.GET, "/api/rides/user/1/summary", Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of());

        // Phase 1: open the circuit
        when(feignClient.getUserRideSummary(anyLong())).thenThrow(error);
        for (int i = 0; i < 5; i++) {
            try { callThroughCb(1L); } catch (Exception ignored) {}
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Phase 2: transition to HALF_OPEN manually
        circuitBreaker.transitionToHalfOpenState();
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        // Phase 3: ride-service recovers
        RideSummaryDTO recovered = new RideSummaryDTO(1L, 10L, 8L, 2L, 500.0, 50.0);
        reset(feignClient);
        when(feignClient.getUserRideSummary(anyLong())).thenReturn(recovered);

        callThroughCb(1L);
        callThroughCb(1L);

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }
}
