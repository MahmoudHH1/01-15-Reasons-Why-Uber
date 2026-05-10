package com.team01.uber.contracts.feign;

import com.team01.uber.contracts.dto.DriverDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "driver-service", url = "${feign.driver-service.url}")
public interface DriverServiceClient {

    @GetMapping("/api/drivers/{id}")
    DriverDTO getDriver(@PathVariable("id") Long id);

    @GetMapping("/api/drivers/{id}/availability")
    boolean getDriverAvailability(@PathVariable("id") Long id);
}
