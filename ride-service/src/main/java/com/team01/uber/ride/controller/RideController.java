package com.team01.uber.ride.controller;

import com.team01.uber.ride.dto.FareEstimateDTO;
import com.team01.uber.ride.dto.FareEstimateRequestDTO;
import com.team01.uber.ride.dto.RideAnalyticsDTO;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.dto.RideDetailsDTO;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.service.RideService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rides")
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping("/estimate")
    public ResponseEntity<FareEstimateDTO> estimateFare(@RequestBody FareEstimateRequestDTO request) {
        return ResponseEntity.ok(rideService.estimateFare(request));
    }

    @PostMapping
    public ResponseEntity<Ride> createRide(@RequestBody Ride ride) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rideService.createRide(ride));
    }

    @GetMapping("/{id}")
    public Ride getRideById(@PathVariable Long id) {
        return rideService.getRideById(id);
    }

    @GetMapping
    public List<Ride> getAllRides() {
        return rideService.getAllRides();
    }

    @GetMapping("/search")
    public List<Ride> searchRides(
            @RequestParam(required = false) RideStatus status,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate) {
        return rideService.searchRides(status, startDate, endDate);
    }

    @PutMapping("/{id}")
    public Ride updateRide(@PathVariable Long id, @RequestBody Ride ride) {
        return rideService.updateRide(id, ride);
    }

    @GetMapping("/{rideId}/details")
    public ResponseEntity<RideDetailsDTO> getRideDetails(@PathVariable Long rideId) {
        return ResponseEntity.ok(rideService.getRideDetails(rideId));
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Ride> cancelRide(@PathVariable Long id) {
        return ResponseEntity.ok(rideService.cancelRide(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRide(@PathVariable Long id) {
        rideService.deleteRide(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/analytics")
    public ResponseEntity<RideAnalyticsDTO> getAnalytics(
            @RequestParam String startDate,
            @RequestParam String endDate) {

        return ResponseEntity.ok(rideService.getRideAnalytics(startDate, endDate));
    }

    @GetMapping("/metadata/search")
    public List<Ride> searchByMetadata(
            @RequestParam("key") String key,
            @RequestParam("value") String value) {
        return rideService.findByMetadata(key, value);
    }

    @PutMapping("/{id}/complete")
    public ResponseEntity<Ride> completeRide(@PathVariable Long id) {
        Ride completedRide = rideService.completeRide(id);
        return ResponseEntity.ok(completedRide);
    }
    
    @PutMapping("/{id}/assign")
    public Ride assignDriver(@PathVariable Long id, @RequestParam Long driverId) {
        return rideService.assignDriver(id, driverId);
    }

    @PostMapping("/{rideId}/record-interaction")
    public ResponseEntity<Map<String, String>> recordInteraction(@PathVariable Long rideId) {
        String message = rideService.recordInteraction(rideId);
        return ResponseEntity.ok(Map.of("message", message));
    }
}
