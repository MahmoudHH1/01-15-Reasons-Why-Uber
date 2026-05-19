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
            log.warn("Recent location not found for driverId={} — driver not actively tracked", driverId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "driver not actively tracked");
        } catch (FeignException e) {
            log.error("Feign call to location-service failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Location service unavailable");
        }
    }

    public LocationDTO getRecentLocationFallback(Long driverId, Exception e) {
        if (e instanceof ResponseStatusException rse) {
            int code = rse.getStatusCode().value();
            if (code == HttpStatus.BAD_REQUEST.value() || code == HttpStatus.NOT_FOUND.value()) {
                log.warn("Surfacing {} from CB fallback for location-service.getRecentLocationForDriver driverId={}", code, driverId);
                throw rse;
            }
        }
        log.warn("Circuit breaker open — fallback for location-service.getRecentLocationForDriver driverId={}: {}", driverId, e.getMessage());
        return new LocationDTO(driverId, 0.0, 0.0, null, 0.0);
    }
}
