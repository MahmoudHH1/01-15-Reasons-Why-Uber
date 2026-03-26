package com.team01.uber.location.controller;

import com.team01.uber.location.dto.BatchLocationRequest;
import com.team01.uber.location.dto.BatchLocationResponse;
import com.team01.uber.location.dto.DriverLocationCreateRequest;
import com.team01.uber.location.dto.PurgeResponse;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.service.LocationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/locations")
public class LocationController {

    private final LocationService locationService;

    public LocationController(LocationService locationService) {
        this.locationService = locationService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping
    public ResponseEntity<Location> create(@RequestBody Location location) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.create(location));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Location> getById(@PathVariable Long id)throws ResponseStatusException {
        return ResponseEntity.ok(locationService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<Location>> getAll() {
        return ResponseEntity.ok(locationService.getAll());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Location> update(@PathVariable Long id, @RequestBody Location location) throws ResponseStatusException {
        return ResponseEntity.ok(locationService.update(id, location));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        locationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchLocationResponse> batchUpdate(@RequestBody BatchLocationRequest request) {
        BatchLocationResponse response = locationService.batchUpdate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/purge")
    public ResponseEntity<PurgeResponse> purgeOldLocations(@RequestParam int olderThanDays) {
        long deletedCount = locationService.purgeOlderThanDays(olderThanDays);
        return ResponseEntity.ok(new PurgeResponse(deletedCount));
    }

    @GetMapping("/driver/{driverId}/latest")
    public ResponseEntity<Location> getLatestByDriverId(@PathVariable Long driverId) throws ResponseStatusException {
        return ResponseEntity.ok(locationService.getLatestByDriverId(driverId));
    }

    @PostMapping("/driver/{driverId}")
    public ResponseEntity<Location> createForDriver(
            @PathVariable Long driverId,
            @Valid @RequestBody DriverLocationCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.createForDriver(driverId, request));
    }
}
