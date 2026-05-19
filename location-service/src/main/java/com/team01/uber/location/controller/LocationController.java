package com.team01.uber.location.controller;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.team01.uber.contracts.dto.LocationDTO;
import com.team01.uber.location.dto.BatchLocationRequest;
import com.team01.uber.location.dto.BatchLocationResponse;
import com.team01.uber.location.dto.DriverLocationCreateRequest;
import com.team01.uber.location.dto.DriverMovementSummaryDTO;
import com.team01.uber.location.dto.LocationAnalyticsDTO;
import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.dto.NearbyDriverDTO;
import com.team01.uber.location.dto.PurgeResponse;
import com.team01.uber.location.dto.StationaryDriverDTO;
import com.team01.uber.location.dto.TrackingRequest;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.model.LocationEvent;
import com.team01.uber.location.repository.LocationEventRepository;
import com.team01.uber.location.service.LocationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/locations")
@Validated
public class LocationController {

    private final LocationService locationService;
    private final LocationEventRepository locationEventRepository;
    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

    public LocationController(LocationService locationService,
                              LocationEventRepository locationEventRepository) {
        this.locationService = locationService;
        this.locationEventRepository = locationEventRepository;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @GetMapping("/analytics")
    public ResponseEntity<LocationAnalyticsDTO> getAnalytics(
            @RequestParam String startDate,
            @RequestParam String endDate) {
        ResponseEntity<LocationAnalyticsDTO> result = ResponseEntity.ok(locationService.getAnalytics(startDate, endDate));
        CompletableFuture.runAsync(() -> locationEventRepository.save(
                new LocationEvent(0L, "ANALYTICS_VIEWED", LocalDateTime.now(),
                        Map.of("startDate", startDate, "endDate", endDate))));
        return result;
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

    @GetMapping("/driver/{driverId}/recent")
    public ResponseEntity<LocationDTO> getRecentLocationForDriver(@PathVariable Long driverId) {
        return ResponseEntity.ok(locationService.getRecentLocationForDriver(driverId));
    }

    @PostMapping("/driver/{driverId}")
    public ResponseEntity<Location> createForDriver(
            @PathVariable Long driverId,
            @Valid @RequestBody DriverLocationCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.createForDriver(driverId, request));
    }

    @GetMapping("/driver/{driverId}/summary")
    public ResponseEntity<DriverMovementSummaryDTO> getDriverMovementSummary(
            @PathVariable Long driverId,
            @RequestParam @NotBlank String startDate,
            @RequestParam @NotBlank String endDate) {
        return ResponseEntity.ok(locationService.getDriverMovementSummary(driverId, startDate, endDate));
    }

    @GetMapping("/stationary")
    public ResponseEntity<List<StationaryDriverDTO>> findStationaryDrivers(
            @RequestParam Double maxSpeed,
            @RequestParam @Min(0) int sinceMinutes,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        return ResponseEntity.ok(locationService.findStationaryDrivers(maxSpeed, sinceMinutes, page, size));
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<NearbyDriverDTO>> findNearbyDrivers(
            @RequestParam Double lat,
            @RequestParam Double lon,
            @RequestParam @Min(0) Double radiusKm,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        return ResponseEntity.ok(locationService.findNearbyDrivers(lat, lon, radiusKm, page, size));
    }

    @GetMapping("/history")
    public ResponseEntity<List<Location>> getLocationsInDateRange(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Long driverId) {
        return ResponseEntity.ok(locationService.getLocationsInDateRange(startDate, endDate, driverId));
    }

    @GetMapping("/metadata/search")
    public ResponseEntity<List<Location>> searchByMetadata(
            @RequestParam String key,
            @RequestParam String operator,
            @RequestParam String value) {
        return ResponseEntity.ok(locationService.filterByMetadata(key, operator, value));
    }

    @GetMapping("/{driverId}/tracking")
    public ResponseEntity<List<LocationTrackingDTO>> getTrackingTimeline(
            @PathVariable Long driverId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        return ResponseEntity.ok(locationService.getTrackingTimeline(driverId, startTime, endTime));
    }

    @PostMapping("/{driverId}/tracking")
    public ResponseEntity<LocationTrackingDTO> recordGpsEvent(
            @PathVariable Long driverId,
            @RequestBody TrackingRequest request) {
        ResponseEntity<LocationTrackingDTO> result = ResponseEntity.status(HttpStatus.CREATED)
                .body(locationService.recordGpsEvent(driverId, request));
        CompletableFuture.runAsync(() -> locationEventRepository.save(
                new LocationEvent(driverId, "TRACKING_RECORDED", LocalDateTime.now(),
                        Map.of("latitude", request.getLatitude() != null ? request.getLatitude() : 0.0,
                               "longitude", request.getLongitude() != null ? request.getLongitude() : 0.0))));
        return result;
    }
}
