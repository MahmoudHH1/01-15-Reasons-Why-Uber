package com.team01.uber.contracts.feign;

import com.team01.uber.contracts.dto.DriverRideSummaryDTO;
import com.team01.uber.contracts.dto.RideDTO;
import com.team01.uber.contracts.dto.RideSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

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

    @GetMapping("/api/rides/driver/{driverId}/summary")
    DriverRideSummaryDTO getDriverRideSummary(
            @PathVariable("driverId") Long driverId,
            @RequestParam("startDate") String startDate,
            @RequestParam("endDate") String endDate
    );

    @GetMapping("/api/rides/driver/{driverId}/active-count")
    long countActiveRidesForDriver(@PathVariable("driverId") Long driverId);

    @GetMapping("/api/rides/driver/{driverId}/stats")
    DriverRideSummaryDTO getDriverStats(@PathVariable("driverId") Long driverId);

    @GetMapping("/api/rides/driver/{driverId}/completed-count")
    long getDriverCompletedRideCount(@PathVariable("driverId") Long driverId);
}