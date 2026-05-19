package com.team01.uber.payment.client;

import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.feign.DriverServiceClient;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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
}
