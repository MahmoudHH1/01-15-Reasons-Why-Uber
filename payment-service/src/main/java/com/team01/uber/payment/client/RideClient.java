package com.team01.uber.payment.client;

import com.team01.uber.contracts.dto.RideDTO;
import com.team01.uber.contracts.feign.RideServiceClient;
import feign.FeignException;
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

    public RideDTO getRide(Long rideId) {
        try {
            log.info("Calling ride-service.getRide for rideId={}", rideId);
            return feignClient.getRide(rideId);
        } catch (FeignException.NotFound e) {
            log.warn("Ride not found for rideId={}", rideId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        } catch (FeignException e) {
            log.warn("ride-service unavailable for getRide rideId={}: {}", rideId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ride service unavailable");
        }
    }
}
