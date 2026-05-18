package com.team01.uber.payment.client;

import com.team01.uber.contracts.dto.RideDTO;
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

    @CircuitBreaker(name = "ride-service", fallbackMethod = "getRideFallback")
    public RideDTO getRide(Long rideId) {
        try {
            log.info("Calling ride-service.getRide for rideId={}", rideId);
            return feignClient.getRide(rideId);
        } catch (FeignException.NotFound e) {
            log.warn("Ride not found for rideId={}", rideId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        } catch (FeignException e) {
            log.error("Feign call to ride-service failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ride service unavailable");
        }
    }

    public RideDTO getRideFallback(Long rideId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getRide rideId={}: {}", rideId, e.getMessage());
        // Return a dummy RideDTO with UNAVAILABLE status
        return new RideDTO(rideId, null, null, "UNAVAILABLE", 0.0);
    }
}
