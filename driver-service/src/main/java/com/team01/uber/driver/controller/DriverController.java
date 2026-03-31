package com.team01.uber.driver.controller;

import com.team01.uber.driver.dto.DriverDocumentAlertDTO;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.service.DriverDocumentService;
import com.team01.uber.driver.service.DriverService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PutMapping("/{id}")
    public Driver updateDriver(@PathVariable Long id, @Valid @RequestBody Driver driver) {
        return driverService.updateDriver(id, driver);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDriver(@PathVariable Long id) {
        driverService.deleteDriver(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/expired")
    public List<DriverDocumentAlertDTO> getDriversWithExpiredDocuments() {
        return driverDocumentService.getDriversWithExpiredDocuments();
    }
}
