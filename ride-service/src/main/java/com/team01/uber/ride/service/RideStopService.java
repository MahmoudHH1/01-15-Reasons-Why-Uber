package com.team01.uber.ride.service;

import com.team01.uber.ride.dto.RideWithStopsDTO;
import com.team01.uber.ride.dto.StopRequestDTO;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.observer.RideEventPublisher;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RideStopService {

    private final RideStopRepository rideStopRepository;
    private final RideRepository rideRepository;
    private final RideEventPublisher rideEventPublisher;

    public RideStopService(RideStopRepository rideStopRepository, RideRepository rideRepository,
                           RideEventPublisher rideEventPublisher) {
        this.rideStopRepository = rideStopRepository;
        this.rideRepository = rideRepository;
        this.rideEventPublisher = rideEventPublisher;
    }

    // S3-F8
    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    @Transactional
    public RideWithStopsDTO addStops(Long rideId, List<StopRequestDTO> requests) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));

        if (requests == null || requests.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stops list must not be empty");
        }

        if (ride.getStatus() != RideStatus.REQUESTED && ride.getStatus() != RideStatus.ACCEPTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot add stops to a ride that is not REQUESTED or ACCEPTED");
        }

        java.util.Set<Integer> seenOrders = new java.util.HashSet<>();
        for (StopRequestDTO req : requests) {
            if (req.latitude() == null || req.longitude() == null || req.address() == null || req.address().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each stop must have latitude, longitude, and address");
            }
            if (req.stopOrder() != null && !seenOrders.add(req.stopOrder())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate stopOrder in request: " + req.stopOrder());
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
            stop.setStopOrder(req.stopOrder() != null ? req.stopOrder() : nextOrder++);
            newStops.add(stop);
        }

        rideStopRepository.saveAll(newStops);

        List<RideStop> allStops = rideStopRepository.findByRideId(rideId)
                .stream()
                .sorted(Comparator.comparingInt(RideStop::getStopOrder))
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", rideId);
        payload.put("addedStopsCount", newStops.size());
        payload.put("totalStops", allStops.size());
        payload.put("stopIds", newStops.stream().map(RideStop::getId).toList());
        payload.put("rideStatus", ride.getStatus().name());
        rideEventPublisher.notifyObservers("RIDE_STOPS_ADDED", payload);

        return new RideWithStopsDTO(ride, allStops);
    }

    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    public RideStop createStop(Long rideId, RideStop stop) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));
        stop.setRide(ride);
        if (stop.getStatus() == null) {
            stop.setStatus(RideStopStatus.PENDING);
        }
        RideStop savedStop = rideStopRepository.save(stop);
        rideEventPublisher.notifyObservers("RIDE_STOP_CREATED", buildStopPayload(savedStop));
        return savedStop;
    }

    public List<RideStop> getStopsByRideId(Long rideId) {
        if (!rideRepository.existsById(rideId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        return rideStopRepository.findByRideId(rideId);
    }

    @Cacheable(value = "ride-service::ride-stop", key = "#stopId")
    public RideStop getStopById(Long rideId, Long stopId) {
        return rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
    }

    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::ride-stop", key = "#stopId"),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
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

        RideStop savedStop = rideStopRepository.save(existing);
        rideEventPublisher.notifyObservers("RIDE_STOP_UPDATED", buildStopPayload(savedStop));
        return savedStop;
    }

    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::ride-stop", key = "#stopId"),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    public void deleteStop(Long rideId, Long stopId) {
        RideStop stop = rideStopRepository.findByIdAndRideId(stopId, rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride stop not found"));
        rideStopRepository.delete(stop);
        rideEventPublisher.notifyObservers("RIDE_STOP_DELETED", buildStopPayload(stop));
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

    private Map<String, Object> buildStopPayload(RideStop stop) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", stop.getRide().getId());
        payload.put("stopId", stop.getId());
        payload.put("stopOrder", stop.getStopOrder());
        payload.put("status", stop.getStatus() == null ? null : stop.getStatus().name());
        payload.put("address", stop.getAddress());
        payload.put("latitude", stop.getLatitude());
        payload.put("longitude", stop.getLongitude());
        payload.put("metadata", stop.getMetadata());
        return payload;
    }
}
