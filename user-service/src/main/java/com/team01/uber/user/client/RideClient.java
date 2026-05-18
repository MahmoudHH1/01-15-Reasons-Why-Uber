package com.team01.uber.user.client;

import com.team01.uber.contracts.dto.RideSummaryDTO;
import com.team01.uber.contracts.feign.RideServiceClient;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Slf4j
public class RideClient {

    private final RideServiceClient feignClient;

    public RideClient(RideServiceClient feignClient) {
        this.feignClient = feignClient;
    }

    @CircuitBreaker(name = "ride-service", fallbackMethod = "getUserRideSummaryFallback")
    public RideSummaryDTO getUserRideSummary(Long userId) {
        try {
            log.info("Calling ride-service.getUserRideSummary for userId={}", userId);
            return feignClient.getUserRideSummary(userId);
        } catch (FeignException.NotFound e) {
            log.warn("Ride summary not found for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride summary not found");
        }
    }

    public RideSummaryDTO getUserRideSummaryFallback(Long userId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getUserRideSummary userId={}: {}", userId, e.getMessage());
        return new RideSummaryDTO(userId, 0L, 0L, 0L, 0.0, 0.0);
    }

    @CircuitBreaker(name = "ride-service", fallbackMethod = "getActiveRideCountFallback")
    public int getActiveRideCount(Long userId) {
        try {
            log.info("Calling ride-service.getActiveRideCount for userId={}", userId);
            return feignClient.getActiveRideCount(userId);
        }
    }

    public int getActiveRideCountFallback(Long userId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getActiveRideCount userId={}: {}", userId, e.getMessage());
        // Safe default: assume 1 active ride to prevent deactivation if service is down
        return 1;
    }

    @CircuitBreaker(name = "ride-service", fallbackMethod = "getCompletedRideCountFallback")
    public long getCompletedRideCount(Long userId) {
        try {
            log.info("Calling ride-service.getCompletedRideCount for userId={}", userId);
            return feignClient.getCompletedRideCount(userId);
        } catch (FeignException.NotFound e) {
            return 0L;
        }
    }

    public long getCompletedRideCountFallback(Long userId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getCompletedRideCount userId={}: {}", userId, e.getMessage());
        return 0L;
    }
}
