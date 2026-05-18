package com.team01.uber.ride.client;

import com.team01.uber.contracts.dto.DriverAvailabilityDTO;
import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.feign.DriverServiceClient;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Component
@Slf4j
public class DriverClient {

    private final DriverServiceClient feignClient;

    public DriverClient(DriverServiceClient feignClient) {
        this.feignClient = feignClient;
    }

    @CircuitBreaker(name = "driver-service", fallbackMethod = "getDriverFallback")
    public DriverDTO getDriver(Long driverId) {
        try {
            log.info("Calling driver-service.getDriver for driverId={}", driverId);
            return feignClient.getDriver(driverId);
        } catch (FeignException.NotFound e) {
            log.warn("Driver not found for driverId={}", driverId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        } catch (FeignException e) {
            log.error("Feign call to driver-service failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Driver service unavailable");
        }
    }

    public DriverDTO getDriverFallback(Long driverId, Exception e) {
        log.warn("Circuit breaker open — fallback for driver-service.getDriver driverId={}: {}", driverId, e.getMessage());
        return new DriverDTO(driverId, "Unknown", "UNAVAILABLE", Map.of());
    }

    @CircuitBreaker(name = "driver-service", fallbackMethod = "getDriverAvailabilityFallback")
    public DriverAvailabilityDTO getDriverAvailability(Long driverId) {
        try {
            log.info("Calling driver-service.getDriverAvailability for driverId={}", driverId);
            return feignClient.getDriverAvailability(driverId);
        } catch (FeignException.NotFound e) {
            log.warn("Driver not found for availability check: {}", driverId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        } catch (FeignException e) {
            log.error("Feign call to driver-service failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Driver service unavailable");
        }
    }

    public DriverAvailabilityDTO getDriverAvailabilityFallback(Long driverId, Exception e) {
        log.warn("Circuit breaker open — fallback for driver-service.getDriverAvailability driverId={}: {}", driverId, e.getMessage());
        return new DriverAvailabilityDTO("UNAVAILABLE");
    }
}
