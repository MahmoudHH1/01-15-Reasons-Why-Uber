package com.team01.uber.ride.client;

import com.team01.uber.contracts.dto.LocationDTO;
import com.team01.uber.contracts.feign.LocationServiceClient;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Slf4j
public class LocationClient {

    private final LocationServiceClient feignClient;

    public LocationClient(LocationServiceClient feignClient) {
        this.feignClient = feignClient;
    }

    @CircuitBreaker(name = "location-service", fallbackMethod = "getRecentLocationFallback")
    public LocationDTO getRecentLocationForDriver(Long driverId) {
        try {
            log.info("Calling location-service.getRecentLocationForDriver for driverId={}", driverId);
            return feignClient.getRecentLocationForDriver(driverId);
        } catch (FeignException.NotFound e) {
            log.warn("Recent location not found for driverId={}", driverId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recent location not found");
        }
    }

    public LocationDTO getRecentLocationFallback(Long driverId, Exception e) {
        log.warn("Circuit breaker open — fallback for location-service.getRecentLocationForDriver driverId={}: {}", driverId, e.getMessage());
        return new LocationDTO(driverId, 0.0, 0.0, null, 0.0);
    }
}
