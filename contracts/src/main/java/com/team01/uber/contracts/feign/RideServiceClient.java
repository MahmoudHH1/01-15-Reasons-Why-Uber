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

    // ── Existing M1 CRUD endpoint ─────────────────────────────────────────────
    // Used by S2-F7 (validate ride before rating) and S5-F4 (validate ride before payment)
    @GetMapping("/api/rides/{id}")
    RideDTO getRide(@PathVariable("id") Long id);

    // ── New endpoints exposed by S3-READ-DB for user-service (S1) ────────────

    // Used by S1-F3 — get user ride summary
    @GetMapping("/api/rides/user/{userId}/summary")
    RideSummaryDTO getUserRideSummary(@PathVariable("userId") Long userId);

    // Used by S1-F4 — check active rides before deactivating user
    @GetMapping("/api/rides/user/{userId}/active-count")
    int getActiveRideCount(@PathVariable("userId") Long userId);

    // Used by S1-F9 — filter users by minimum completed rides
    @GetMapping("/api/rides/user/{userId}/completed-count")
    long getCompletedRideCount(@PathVariable("userId") Long userId);

    // ── New endpoints exposed by S3-READ-DB for driver-service (S2) ──────────

    // Used by S2-F3 (earnings) and S2-F12 (dashboard)
    @GetMapping("/api/rides/driver/{driverId}/summary")
    DriverRideSummaryDTO getDriverRideSummary(
            @PathVariable("driverId") Long driverId,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate
    );

    // Used by S2-F4 — check active rides before going OFFLINE
    @GetMapping("/api/rides/driver/{driverId}/active-count")
    int getDriverActiveRideCount(@PathVariable("driverId") Long driverId);

    // Used by S2-F6 — top rated drivers report
    @GetMapping("/api/rides/driver/{driverId}/completed-count")
    long getDriverCompletedRideCount(@PathVariable("driverId") Long driverId);
}