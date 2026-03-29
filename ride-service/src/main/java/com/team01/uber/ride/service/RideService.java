package com.team01.uber.ride.service;

import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.repository.RideRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RideService {

    private final RideRepository rideRepository;

    public RideService(RideRepository rideRepository) {
        this.rideRepository = rideRepository;
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

        existing.setFare(updated.getFare());
        existing.setMetadata(updated.getMetadata());
        existing.setCompletedAt(updated.getCompletedAt());

        return rideRepository.save(existing);
    }

    public void deleteRide(Long id) {
        if (!rideRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        rideRepository.deleteById(id);
    }

    @Transactional
    public Ride assignDriver(Long rideId, Long driverId) {
        Ride ride = getRideById(rideId);

        if (ride.getStatus() != RideStatus.REQUESTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only rides with status REQUESTED can be assigned a driver");
        }

        ride.setDriverId(driverId);
        ride.setStatus(RideStatus.ACCEPTED);
        rideRepository.save(ride);

        if (!rideRepository.driverExists(driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        if (!rideRepository.isDriverAvailable(driverId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Driver is not available");
        }

        rideRepository.setDriverBusy(driverId);

        return ride;
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
