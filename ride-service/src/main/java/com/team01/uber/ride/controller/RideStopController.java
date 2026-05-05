package com.team01.uber.ride.controller;

import com.team01.uber.ride.dto.AddStopsRequest;
import com.team01.uber.ride.dto.RideWithStopsDTO;
import com.team01.uber.ride.dto.StopRequestDTO;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.service.RideStopService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rides/{rideId}/stops")
public class RideStopController {

    private final RideStopService rideStopService;

    public RideStopController(RideStopService rideStopService) {
        this.rideStopService = rideStopService;
    }

    @PostMapping
    public ResponseEntity<RideWithStopsDTO> addStops(@PathVariable Long rideId, @RequestBody AddStopsRequest body) {
        List<StopRequestDTO> stops = body == null ? null : body.stops();
        return ResponseEntity.status(HttpStatus.CREATED).body(rideStopService.addStops(rideId, stops));
    }

    @GetMapping
    public List<RideStop> getStopsByRideId(@PathVariable Long rideId) {
        return rideStopService.getStopsByRideId(rideId);
    }

    @GetMapping("/{stopId}")
    public RideStop getStopById(@PathVariable Long rideId, @PathVariable Long stopId) {
        return rideStopService.getStopById(rideId, stopId);
    }

    @PutMapping("/{stopId}")
    public RideStop updateStop(@PathVariable Long rideId, @PathVariable Long stopId, @RequestBody RideStop stop) {
        return rideStopService.updateStop(rideId, stopId, stop);
    }

    @DeleteMapping("/{stopId}")
    public ResponseEntity<Void> deleteStop(@PathVariable Long rideId, @PathVariable Long stopId) {
        rideStopService.deleteStop(rideId, stopId);
        return ResponseEntity.noContent().build();
    }
}
