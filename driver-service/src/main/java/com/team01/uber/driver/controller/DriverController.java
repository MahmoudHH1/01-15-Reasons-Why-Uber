package com.team01.uber.driver.controller;

import com.team01.uber.driver.dto.DriverDashboardDTO;
import com.team01.uber.driver.dto.DriverDocumentAlertDTO;
import com.team01.uber.driver.dto.DriverEarningsDTO;
import com.team01.uber.driver.dto.DriverSearchResultDTO;
import com.team01.uber.driver.dto.RateDriverRequest;
import com.team01.uber.driver.dto.TopDriverDTO;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.service.DriverDocumentService;
import com.team01.uber.driver.service.DriverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverService driverService;
    private final DriverDocumentService driverDocumentService;

    public DriverController(DriverService driverService, DriverDocumentService driverDocumentService) {
        this.driverService = driverService;
        this.driverDocumentService = driverDocumentService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping
    public ResponseEntity<Driver> createDriver(@Valid @RequestBody Driver driver) {
        return ResponseEntity.status(HttpStatus.CREATED).body(driverService.createDriver(driver));
    }

    @GetMapping("/{id}")
    public Driver getDriverById(@PathVariable Long id) {
        return driverService.getDriverById(id);
    }

    @GetMapping
    public List<Driver> getAllDrivers() {
        return driverService.getAllDrivers();
    }

    @GetMapping("/reports/top-rated")
    public List<TopDriverDTO> getTopRatedDrivers(@RequestParam int limit) {
        return driverService.getTopRatedDrivers(limit);
    }
    private static final Set<String> ALLOWED_VEHICLE_TYPES =
            Set.of("SEDAN", "SUV", "HATCHBACK", "VAN", "LUXURY");

    @GetMapping("/vehicle-type")
    public List<Driver> filterByVehicleType(@RequestParam String type,
                                            @RequestParam(required = false) DriverStatus status) {
        if (type == null || !ALLOWED_VEHICLE_TYPES.contains(type.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown vehicle type: " + type);
        }
        return driverService.filterByVehicleType(type, status);
    }
    @GetMapping("/search")
    public List<Driver> searchDrivers(@RequestParam(required = false) DriverStatus status,
                                      @RequestParam(required = false, defaultValue = "0.0") Double minRating,
                                      @RequestParam(required = false, defaultValue = "5.0") Double maxRating) {
        return driverService.searchDrivers(status, minRating, maxRating);
    }

    @GetMapping("/search/full-text")
    public List<DriverSearchResultDTO> searchDriversFullText(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String vehicleType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) Double maxRating) {
        return driverService.searchDriversFullText(query, vehicleType, status, minRating, maxRating);
    }

    @PutMapping("/{id}")
    public Driver updateDriver(@PathVariable Long id, @Valid @RequestBody Driver driver) {
        return driverService.updateDriver(id, driver);
    }

    @GetMapping("/{id}/availability")
    public ResponseEntity<Map<String, String>> getDriverAvailability(@PathVariable Long id) {
        return ResponseEntity.ok(driverService.getDriverAvailability(id));
    }

    @PutMapping("/{id}/availability")
    public ResponseEntity<Void> updateAvailability(@PathVariable Long id,
                                                   @RequestBody Map<String, String> body) {
        String raw = body.get("status");
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            DriverStatus status = DriverStatus.valueOf(raw.toUpperCase());
            driverService.updateAvailability(id, status);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PutMapping("/{id}/vehicle")
    public Driver updateVehicleDetails(@PathVariable Long id, @RequestBody Map<String, Object> vehicleUpdates) {
        return driverService.updateVehicleDetails(id, vehicleUpdates);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDriver(@PathVariable Long id) {
        driverService.deleteDriver(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<Void> indexDriver(@PathVariable Long id) {
        driverService.indexDriver(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/documents/expired")
    public List<DriverDocumentAlertDTO> getDriversWithExpiredDocuments() {
        return driverDocumentService.getDriversWithExpiredDocuments();
    }
    @PostMapping("/{id}/rate")
    public ResponseEntity<Driver> rateDriver(@PathVariable Long id,
                                             @Valid @RequestBody RateDriverRequest request,
                                             @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        Driver updated = driverService.rateDriver(id, request.getRideId(), request.getRating(), userId);
        return ResponseEntity.ok(updated);
    }
    @GetMapping("/{id}/earnings")
    public DriverEarningsDTO getEarningsSummary(@PathVariable Long id,
                                                @RequestParam LocalDate startDate,
                                                @RequestParam LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate must be on or before endDate");
        }
        return driverService.getEarningsSummary(id, startDate, endDate);
    }

    @GetMapping("/{id}/dashboard")
    public ResponseEntity<DriverDashboardDTO> getDriverDashboard(@PathVariable Long id) {
        return ResponseEntity.ok(driverService.getDriverDashboard(id));
    }
}

