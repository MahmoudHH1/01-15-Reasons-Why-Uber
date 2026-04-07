package com.team01.uber.ride.service;

import com.team01.uber.ride.dto.FareEstimateDTO;
import com.team01.uber.ride.dto.FareEstimateRequestDTO;
import com.team01.uber.ride.dto.RideDetailsDTO;
import com.team01.uber.ride.dto.StopDetailDTO;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class RideService {

    private final RideRepository rideRepository;
    private final RideStopRepository rideStopRepository;

    public RideService(RideRepository rideRepository, RideStopRepository rideStopRepository) {
        this.rideRepository = rideRepository;
        this.rideStopRepository = rideStopRepository;
    }

    public Ride createRide(Ride ride) {
        ride.setRequestedAt(LocalDateTime.now());
        if (ride.getStatus() == null) {
            ride.setStatus(RideStatus.REQUESTED);
        }
        return rideRepository.save(ride);
    }

    public Ride getRideById(Long id) {
        return rideRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));
    }

    public List<Ride> getAllRides() {
        return rideRepository.findAll();
    }

    public Ride updateRide(Long id, Ride updated) {
        Ride existing = getRideById(id);

        validateRequiredUpdateKeys(updated);

        existing.setDriverId(updated.getDriverId());
        existing.setPickupLatitude(updated.getPickupLatitude());
        existing.setPickupLongitude(updated.getPickupLongitude());
        existing.setDropoffLatitude(updated.getDropoffLatitude());
        existing.setDropoffLongitude(updated.getDropoffLongitude());
        existing.setStatus(updated.getStatus());

        existing.setFare(updated.getFare()); // nullable field on the DB
        existing.setMetadata(updated.getMetadata()); // nullable field on the DB
        existing.setCompletedAt(updated.getCompletedAt()); // nullable field on the DB

        return rideRepository.save(existing);
    }

    public RideDetailsDTO getRideDetails(Long rideId) {
        Ride ride = getRideById(rideId);

        List<StopDetailDTO> stops = rideStopRepository.findByRideId(rideId)
                .stream()
                .sorted(Comparator.comparingInt(RideStop::getStopOrder))
                .map(s -> new StopDetailDTO(s.getId(), s.getStopOrder(), s.getAddress(),
                        s.getLatitude(), s.getLongitude(), s.getStatus(), s.getMetadata()))
                .toList();

        long completedStops = stops.stream().filter(s -> s.status() == RideStopStatus.REACHED).count();

        return new RideDetailsDTO(ride.getId(), ride.getUserId(), ride.getDriverId(),
                ride.getStatus(), ride.getFare(), ride.getMetadata(),
                stops, stops.size(), completedStops);
    }

    public void deleteRide(Long id) {
        if (!rideRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        rideRepository.deleteById(id);
    }

    public FareEstimateDTO estimateFare(FareEstimateRequestDTO request) {
        if (request.pickupLatitude() == null || request.pickupLongitude() == null ||
            request.dropoffLatitude() == null || request.dropoffLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "All coordinate fields are required");
        }

        double latDiff = request.dropoffLatitude() - request.pickupLatitude();
        double lonDiff = request.dropoffLongitude() - request.pickupLongitude();
        double distance = Math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111;

        double duration = (distance / 40.0) * 60.0;

        long activeRides = rideRepository.countActiveRidesNearby(
                request.pickupLatitude(), request.pickupLongitude());

        double surgeMultiplier;
        if (activeRides > 20) {
            surgeMultiplier = 2.0;
        } else if (activeRides > 10) {
            surgeMultiplier = 1.5;
        } else {
            surgeMultiplier = 1.0;
        }

        double fare = 15.0 * distance * surgeMultiplier;

        return new FareEstimateDTO(distance, duration, fare, surgeMultiplier);
    }

    private void validateRequiredUpdateKeys(Ride updated) {
        if (updated.getDriverId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Driver ID cannot be null");
        }

        if (updated.getPickupLatitude() == null || updated.getPickupLongitude() == null ||
            updated.getDropoffLatitude() == null || updated.getDropoffLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Location fields (pickup and dropoff latitude/longitude) cannot be null");
        }

        if (updated.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride status cannot be null");
        }
    }
}
