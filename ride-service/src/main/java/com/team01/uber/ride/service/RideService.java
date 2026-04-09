package com.team01.uber.ride.service;

import com.team01.uber.ride.dto.FareEstimateDTO;
import com.team01.uber.ride.dto.FareEstimateRequestDTO;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.repository.RideRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
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

        existing.setFare(updated.getFare()); // nullable field on the DB
        existing.setMetadata(updated.getMetadata()); // nullable field on the DB
        existing.setCompletedAt(updated.getCompletedAt()); // nullable field on the DB

        return rideRepository.save(existing);
    }

    public List<Ride> searchRides(RideStatus status, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        if (status == null) {
            return rideRepository.findByRequestedAtBetweenOrderByRequestedAtDesc(start, end);
        }
        return rideRepository.findByRequestedAtBetweenAndStatusOrderByRequestedAtDesc(start, end, status);
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


    @Transactional
    public Ride completeRide(Long id) {
        Ride ride = getRideById(id);

        // Validate status is IN_PROGRESS - throws 400 if not
        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only rides with IN_PROGRESS status can be completed. Current status: " + ride.getStatus()
            );
        }

        // Validate driver is assigned
        if (ride.getDriverId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Cannot complete ride without assigned driver"
            );
        }

        // Validate driver status is busy
        if (!rideRepository.isDriverBusy(ride.getDriverId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Driver must be BUSY to complete a ride. Driver ID: " + ride.getDriverId()
            );
        }

        // Set status to COMPLETED and set completedAt timestamp
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(LocalDateTime.now());

        // Calculate fare if not already set
        if (ride.getFare() == null) {
            FareEstimateRequestDTO fareRequest = new FareEstimateRequestDTO(
                    ride.getPickupLatitude(),
                    ride.getPickupLongitude(),
                    ride.getDropoffLatitude(),
                    ride.getDropoffLongitude()
            );
            FareEstimateDTO fareEstimate = estimateFare(fareRequest);
            ride.setFare(fareEstimate.estimatedFare());
        }

        // Update driver status to AVAILABLE
        rideRepository.setDriverAvailable(ride.getDriverId());

        // Create payment record
        String paymentMethod = rideRepository.getDefaultPaymentMethod(ride.getUserId());
        
        rideRepository.createPayment(
                ride.getId(),
                ride.getUserId(),
                ride.getFare(),
                "CASH",
                "PENDING",
                LocalDateTime.now()
        );

        // Save ride and return the updated entity
        return rideRepository.save(ride);

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
