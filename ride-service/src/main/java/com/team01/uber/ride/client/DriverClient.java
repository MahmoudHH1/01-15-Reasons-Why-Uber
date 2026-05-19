package com.team01.uber.ride.client;

import com.team01.uber.contracts.dto.DriverAvailabilityDTO;
import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.feign.DriverServiceClient;
import feign.FeignException;
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

    public DriverDTO getDriver(Long driverId) {
        try {
            log.info("Calling driver-service.getDriver for driverId={}", driverId);
            return feignClient.getDriver(driverId);
        } catch (FeignException.NotFound e) {
            log.warn("Driver not found for driverId={}", driverId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        } catch (FeignException e) {
            log.warn("driver-service unavailable for getDriver driverId={}: {}", driverId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Driver service unavailable");
        }
    }

    public DriverAvailabilityDTO getDriverAvailability(Long driverId) {
        try {
            log.info("Calling driver-service.getDriverAvailability for driverId={}", driverId);
            return feignClient.getDriverAvailability(driverId);
        } catch (FeignException.NotFound e) {
            log.warn("Driver not found for availability check: {}", driverId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        } catch (FeignException e) {
            log.warn("driver-service unavailable for getDriverAvailability driverId={}: {}", driverId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Driver service unavailable");
        }
    }
}
