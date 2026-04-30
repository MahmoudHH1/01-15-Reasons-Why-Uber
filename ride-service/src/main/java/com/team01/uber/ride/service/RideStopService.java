package com.team01.uber.ride.service;

import com.team01.uber.ride.dto.RideWithStopsDTO;
import com.team01.uber.ride.dto.StopRequestDTO;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class RideStopService {

    private final RideStopRepository rideStopRepository;
    private final RideRepository rideRepository;

    public RideStopService(RideStopRepository rideStopRepository, RideRepository rideRepository) {
        this.rideStopRepository = rideStopRepository;
        this.rideRepository = rideRepository;
    }

    // S3-F8
    @Transactional
    public RideWithStopsDTO addStops(Long rideId, List<StopRequestDTO> requests) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));

        if (ride.getStatus() != RideStatus.REQUESTED && ride.getStatus() != RideStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot add stops to a ride that is not REQUESTED or ACCEPTED");
        }

        for (StopRequestDTO req : requests) {
            if (req.latitude() == null || req.longitude() == null || req.address() == null || req.address().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each stop must have latitude, longitude, and address");
            }
        }

        Integer maxOrder = rideStopRepository.findMaxStopOrderByRideId(rideId);
        int nextOrder = (maxOrder == null ? 0 : maxOrder) + 1;

        List<RideStop> newStops = new ArrayList<>();
        for (StopRequestDTO req : requests) {
            RideStop stop = new RideStop();
            stop.setRide(ride);
            stop.setLatitude(req.latitude());
            stop.setLongitude(req.longitude());
            stop.setAddress(req.address());
            stop.setMetadata(req.metadata());
            stop.setStatus(RideStopStatus.PENDING);
            stop.setStopOrder(nextOrder++);
            newStops.add(stop);
        }

        rideStopRepository.saveAll(newStops);

        List<RideStop> allStops = rideStopRepository.findByRideId(rideId)
                .stream()
                .sorted(Comparator.comparingInt(RideStop::getStopOrder))
                .toList();

        return new RideWithStopsDTO(ride, allStops);
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

    @Cacheable(value = "ride-service::rideStop", key = "#rideId + '-' + #stopId")
    public RideStop getStopById(Long rideId, Long stopId) {
        return rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
    }

    public RideStop updateStop(Long rideId, Long stopId, RideStop updated) {
        RideStop existing = rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));

        validateRequiredUpdateKeys(updated);

        existing.setStopOrder(updated.getStopOrder());
        existing.setLatitude(updated.getLatitude());
        existing.setLongitude(updated.getLongitude());
        existing.setAddress(updated.getAddress());
        existing.setStatus(updated.getStatus());

        existing.setMetadata(updated.getMetadata()); // nullable field on the DB

        return rideStopRepository.save(existing);
    }

    public void deleteStop(Long rideId, Long stopId) {
        RideStop stop = rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
        rideStopRepository.delete(stop);
    }

    private void validateRequiredUpdateKeys(RideStop updated) {
        if (updated.getStopOrder() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: stopOrder");
        }

        if (updated.getLatitude() == null || updated.getLongitude() == null ) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: latitude or longitude");
        }

        if (updated.getAddress() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: address");
        }

        if (updated.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: status");
        }
    }
}
