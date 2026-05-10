package com.team01.uber.contracts.feign;

import com.team01.uber.contracts.dto.RideDTO;
import com.team01.uber.contracts.dto.RideSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "ride-service", url = "${feign.ride-service.url}")
public interface RideServiceClient {

    @GetMapping("/api/rides/{id}")
    RideDTO getRide(@PathVariable("id") Long id);

    @GetMapping("/api/rides/user/{userId}/summary")
    RideSummaryDTO getUserRideSummary(@PathVariable("userId") Long userId);

    @GetMapping("/api/rides/user/{userId}/active-count")
    int getActiveRideCount(@PathVariable("userId") Long userId);

    @GetMapping("/api/rides/user/{userId}/completed-count")
    long getCompletedRideCount(@PathVariable("userId") Long userId);
}
