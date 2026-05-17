package com.team01.uber.contracts.feign;

import com.team01.uber.contracts.dto.DriverAvailabilityDTO;
import com.team01.uber.contracts.dto.DriverDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "driver-service", url = "${feign.driver-service.url}")
public interface DriverServiceClient {

    // Used by S3-F2 (assign driver), S3-F4 (complete ride pre-check),
    // S3-F11 (record interaction), S3-F12 (recommendations enrichment)
    @GetMapping("/api/drivers/{id}")
    DriverDTO getDriver(@PathVariable("id") Long id);

    // Used by S3-F2 (check AVAILABLE before assign),
    // S3-F4 (check BUSY as saga pre-check)
    // Returns {"status": String} per §4 — not a plain boolean
    @GetMapping("/api/drivers/{id}/availability")
    DriverAvailabilityDTO getDriverAvailability(@PathVariable("id") Long id);
}