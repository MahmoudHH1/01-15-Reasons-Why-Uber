package com.team01.uber.driver.client;

import com.team01.uber.contracts.dto.DriverRideSummaryDTO;
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
        }
    }

    public RideDTO getRideFallback(Long rideId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getRide rideId={}: {}", rideId, e.getMessage());
        return new RideDTO(rideId, null, null, "UNAVAILABLE", 0.0);
    }

    @CircuitBreaker(name = "ride-service", fallbackMethod = "countActiveRidesForDriverFallback")
    public long countActiveRidesForDriver(Long driverId) {
        try {
            log.info("Calling ride-service.countActiveRidesForDriver for driverId={}", driverId);
            return feignClient.countActiveRidesForDriver(driverId);
        }
    }

    public long countActiveRidesForDriverFallback(Long driverId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.countActiveRidesForDriver driverId={}: {}", driverId, e.getMessage());
        // Safe default: assume 1 active ride to prevent state transitions if service is down
        return 1L;
    }

    @CircuitBreaker(name = "ride-service", fallbackMethod = "getDriverRideSummaryFallback")
    public DriverRideSummaryDTO getDriverRideSummary(Long driverId, String startDate, String endDate) {
        try {
            log.info("Calling ride-service.getDriverRideSummary for driverId={}", driverId);
            return feignClient.getDriverRideSummary(driverId, startDate, endDate);
        }
    }

    public DriverRideSummaryDTO getDriverRideSummaryFallback(Long driverId, String startDate, String endDate, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getDriverRideSummary driverId={}: {}", driverId, e.getMessage());
        return DriverRideSummaryDTO.empty(driverId);
    }

    @CircuitBreaker(name = "ride-service", fallbackMethod = "getDriverStatsFallback")
    public DriverRideSummaryDTO getDriverStats(Long driverId) {
        try {
            log.info("Calling ride-service.getDriverStats for driverId={}", driverId);
            return feignClient.getDriverStats(driverId);
        }
    }

    public DriverRideSummaryDTO getDriverStatsFallback(Long driverId, Exception e) {
        log.warn("Circuit breaker open — fallback for ride-service.getDriverStats driverId={}: {}", driverId, e.getMessage());
        return DriverRideSummaryDTO.empty(driverId);
    }
}
