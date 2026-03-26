package com.team01.uber.ride.service;

import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class RideStopService {

    private final RideStopRepository rideStopRepository;
    private final RideRepository rideRepository;

    public RideStopService(RideStopRepository rideStopRepository, RideRepository rideRepository) {
        this.rideStopRepository = rideStopRepository;
        this.rideRepository = rideRepository;
    }

    public RideStop createStop(Long rideId, RideStop stop) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));
        stop.setRide(ride);
        if (stop.getStatus() == null) {
            stop.setStatus(RideStopStatus.PENDING);
        }
        return rideStopRepository.save(stop);
    }

    public List<RideStop> getStopsByRideId(Long rideId) {
        if (!rideRepository.existsById(rideId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        return rideStopRepository.findByRideId(rideId);
    }

    public RideStop getStopById(Long rideId, Long stopId) {
        return rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
    }

    public RideStop updateStop(Long rideId, Long stopId, RideStop updated) {
        RideStop existing = rideStopRepository.findByIdAndRideId(stopId, rideId)
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
        RideStop stop = rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
        rideStopRepository.delete(stop);
    }
}
