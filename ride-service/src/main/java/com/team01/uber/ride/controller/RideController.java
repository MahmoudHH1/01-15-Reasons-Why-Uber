package com.team01.uber.ride.controller;

import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.service.RideService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PutMapping("/{id}")
    public Ride updateRide(@PathVariable Long id, @RequestBody Ride ride) {
        return rideService.updateRide(id, ride);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRide(@PathVariable Long id) {
        rideService.deleteRide(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/assign")
    public Ride assignDriver(@PathVariable Long id, @RequestParam Long driverId) {
        return rideService.assignDriver(id, driverId);
    }
}
