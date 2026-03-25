package com.team01.uber.ride.service;

import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.repository.RideStopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RideStopService {

    private final RideStopRepository rideStopRepository;
    private final RideService rideService;

    public RideStopService(RideStopRepository rideStopRepository, RideService rideService) {
        this.rideStopRepository = rideStopRepository;
        this.rideService = rideService;
    }

    public RideStop createStop(Long rideId, RideStop stop) {
        Ride ride = rideService.getRideById(rideId);
        stop.setRide(ride);
        if (stop.getStatus() == null) {
            stop.setStatus(RideStopStatus.PENDING);
        }
        return rideStopRepository.save(stop);
    }

    public List<RideStop> getStopsByRideId(Long rideId) {
        rideService.getRideById(rideId);
        return rideStopRepository.findByRideId(rideId);
    }

    public RideStop getStopById(Long rideId, Long stopId) {
        rideService.getRideById(rideId);
        return rideStopRepository.findById(stopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
    }

    public RideStop updateStop(Long rideId, Long stopId, RideStop updated) {
        rideService.getRideById(rideId);
        RideStop existing = rideStopRepository.findById(stopId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
        existing.setStopOrder(updated.getStopOrder());
        existing.setLatitude(updated.getLatitude());
        existing.setLongitude(updated.getLongitude());
        existing.setAddress(updated.getAddress());
        existing.setStatus(updated.getStatus());
        existing.setMetadata(updated.getMetadata());
        return rideStopRepository.save(existing);
    }

    public void deleteStop(Long rideId, Long stopId) {
        rideService.getRideById(rideId);
        if (!rideStopRepository.existsById(stopId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found");
        }
        rideStopRepository.deleteById(stopId);
    }
}
