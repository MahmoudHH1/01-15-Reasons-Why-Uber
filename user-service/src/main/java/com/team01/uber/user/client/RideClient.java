package com.team01.uber.user.client;

import com.team01.uber.contracts.dto.RideSummaryDTO;
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

    public RideSummaryDTO getUserRideSummary(Long userId) {
        try {
            log.info("Calling ride-service.getUserRideSummary for userId={}", userId);
            return feignClient.getUserRideSummary(userId);
        } catch (FeignException.NotFound e) {
            log.warn("Ride summary not found for userId={}", userId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride summary not found");
        } catch (FeignException e) {
            log.warn("ride-service unavailable for getUserRideSummary userId={}: {}", userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ride service unavailable");
        }
    }

    public int getActiveRideCount(Long userId) {
        try {
            log.info("Calling ride-service.getActiveRideCount for userId={}", userId);
            return feignClient.getActiveRideCount(userId);
        } catch (FeignException.NotFound e) {
            return 0;
        } catch (FeignException e) {
            log.warn("ride-service unavailable for getActiveRideCount userId={}: {}", userId, e.getMessage());
            return 1;
        }
    }

    public long getCompletedRideCount(Long userId) {
        try {
            log.info("Calling ride-service.getCompletedRideCount for userId={}", userId);
            return feignClient.getCompletedRideCount(userId);
        } catch (FeignException.NotFound e) {
            return 0L;
        } catch (FeignException e) {
            log.warn("ride-service unavailable for getCompletedRideCount userId={}: {}", userId, e.getMessage());
            return 0L;
        }
    }
}
