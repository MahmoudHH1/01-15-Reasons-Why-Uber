package com.team01.uber.ride.client;

import com.team01.uber.contracts.dto.LocationDTO;
import com.team01.uber.contracts.feign.LocationServiceClient;
import feign.FeignException;
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

    public LocationDTO getRecentLocationForDriver(Long driverId) {
        try {
            log.info("Calling location-service.getRecentLocationForDriver for driverId={}", driverId);
            return feignClient.getRecentLocationForDriver(driverId);
        } catch (FeignException.NotFound e) {
            log.warn("Recent location not found for driverId={} — driver not actively tracked", driverId);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "driver not actively tracked");
        } catch (FeignException e) {
            log.warn("location-service unavailable for getRecentLocationForDriver driverId={}: {}", driverId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Location service unavailable");
        }
    }
}
