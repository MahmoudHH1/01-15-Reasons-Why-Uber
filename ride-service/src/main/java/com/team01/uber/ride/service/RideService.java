package com.team01.uber.ride.service;

import com.team01.uber.ride.dto.*;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.observer.RideEventPublisher;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import jakarta.transaction.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.cache.annotation.Cacheable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class RideService {

    private final RideRepository rideRepository;
    private final RideStopRepository rideStopRepository;
    private final RideEventPublisher rideEventPublisher;

    public RideService(RideRepository rideRepository, RideStopRepository rideStopRepository,
                       RideEventPublisher rideEventPublisher) {
        this.rideRepository = rideRepository;
        this.rideStopRepository = rideStopRepository;
        this.rideEventPublisher = rideEventPublisher;
    }

    @Caching(evict = {
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F3", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    public Ride createRide(Ride ride) {
        ride.setRequestedAt(LocalDateTime.now());
        if (ride.getStatus() == null) {
            ride.setStatus(RideStatus.REQUESTED);
        }
        Ride savedRide = rideRepository.save(ride);
        rideEventPublisher.notifyObservers("RIDE_CREATED", buildRidePayload(savedRide));
        return savedRide;
    }

    @Cacheable(value="ride-service::ride", key="#id")
    public Ride getRideById(Long id) {
        return rideRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));
    }

    public List<Ride> getAllRides() {
        return rideRepository.findAll();
    }

    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F3", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F5", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
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

        Ride savedRide = rideRepository.save(existing);
        rideEventPublisher.notifyObservers("RIDE_UPDATED", buildRidePayload(savedRide));
        return savedRide;
    }

    // S3-F9
    @Cacheable(value = "ride-service::S3-F9", key="#rideId")
    public RideDetailsDTO getRideDetails(Long rideId) {
        Ride ride = getRideById(rideId);

        List<StopDetailDTO> stops = rideStopRepository.findByRideId(rideId)
                .stream()
                .sorted(Comparator.comparingInt(RideStop::getStopOrder))
                .map(s -> new StopDetailDTO(s.getId(), s.getStopOrder(), s.getAddress(),
                        s.getLatitude(), s.getLongitude(), s.getStatus(), s.getMetadata()))
                .toList();

        long completedStops = stops.stream().filter(s -> s.status() == RideStopStatus.REACHED).count();

        return RideDetailsDTO.builder()
                .rideId(ride.getId())
                .userId(ride.getUserId())
                .driverId(ride.getDriverId())
                .status(ride.getStatus())
                .fare(ride.getFare())
                .metadata(ride.getMetadata())
                .stops(stops)
                .totalStops(stops.size())
                .completedStops(completedStops)
                .build();
    }

    // S3-F7
    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F3", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true),
            @CacheEvict(value = "driver-service::S2-F12", key = "#result.driverId", condition = "#result != null && #result.driverId != null")
    })
    @Transactional
    public Ride cancelRide(Long id) {
        Ride ride = getRideById(id);

        Set<RideStatus> activeStatuses = EnumSet.of(RideStatus.REQUESTED, RideStatus.ACCEPTED);
        if (!activeStatuses.contains(ride.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only REQUESTED or ACCEPTED rides can be cancelled");
        }

        if (ride.getDriverId() != null) {
            if (!rideRepository.driverExists(ride.getDriverId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned driver not found or is not available");
            }

            if(rideRepository.setDriverAvailable(ride.getDriverId()) == 0){
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to set driver status to AVAILABLE");
            }
        }

        ride.setStatus(RideStatus.CANCELLED);
        Ride savedRide = rideRepository.save(ride);
        rideEventPublisher.notifyObservers("RIDE_CANCELLED", buildRidePayload(savedRide));
        return savedRide;
    }

    // S3-F1
    @Cacheable(value = "ride-service::S3-F1", key="#status + '-' + #startDate.toString() + '-' + #endDate.toString()")
    public List<Ride> searchRides(RideStatus status, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        if (status == null) {
            return rideRepository.findByRequestedAtBetweenOrderByRequestedAtDesc(start, end);
        }
        return rideRepository.findByRequestedAtBetweenAndStatusOrderByRequestedAtDesc(start, end, status);
    }

    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F3", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F5", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    public void deleteRide(Long id) {
        Ride ride = getRideById(id);
        rideRepository.deleteById(id);
        rideEventPublisher.notifyObservers("RIDE_DELETED", buildRidePayload(ride));
    }

    // S3-F2
    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true),
            @CacheEvict(value = "driver-service::S2-F12", key = "#driverId")
    })
    @Transactional
    public Ride assignDriver(Long rideId, Long driverId) {
        Ride ride = getRideById(rideId);

        if (ride.getStatus() != RideStatus.REQUESTED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only rides with status REQUESTED can be assigned a driver");
        }

        if (!rideRepository.driverExists(driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        if (!rideRepository.isDriverAvailable(driverId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Driver is not available");
        }

        ride.setDriverId(driverId);
        ride.setStatus(RideStatus.ACCEPTED);
        Ride savedRide = rideRepository.save(ride);

        if(rideRepository.setDriverBusy(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to set driver status to BUSY. Driver may have become unavailable.");
        }

        rideEventPublisher.notifyObservers("RIDE_DRIVER_ASSIGNED", buildRidePayload(savedRide));
        return savedRide;
    }

    // S3-F3
    @Cacheable(value = "ride-service::S3-F3", key="#request.pickupLatitude + '-' + #request.pickupLongitude + '-' + #request.dropoffLatitude + '-' + #request.dropoffLongitude")
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

        return FareEstimateDTO.builder()
                .estimatedDistance(distance)
                .estimatedDuration(duration)
                .estimatedFare(fare)
                .surgeMultiplier(surgeMultiplier)
                .build();
    }

    //S3-F6
    @Cacheable(value= "ride-service::S3-F6", key="#startDateStr + '-' + #endDateStr")
    public RideAnalyticsDTO getRideAnalytics(String startDateStr, String endDateStr) {

        // Parse the strings using our helper methods below
        LocalDateTime start = parseStartDate(startDateStr);
        LocalDateTime end = parseEndDate(endDateStr);

        List<Ride> rides = rideRepository.findByRequestedAtBetweenOrderByRequestedAtDesc(start, end);

        long totalRides = rides.size();

        long completedRides = rides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED)
                .count();

        long cancelledRides = rides.stream()
                .filter(r -> r.getStatus() == RideStatus.CANCELLED)
                .count();

        double totalRevenue = rides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED && r.getFare() != null)
                .mapToDouble(Ride::getFare)
                .sum();

        double averageFare = completedRides > 0
                ? totalRevenue / completedRides
                : 0.0;

        double completionRate = totalRides > 0
                ? ((double) completedRides / totalRides) * 100.0
                : 0.0;

        return RideAnalyticsDTO.builder()
                .totalRides(totalRides)
                .completedRides(completedRides)
                .cancelledRides(cancelledRides)
                .totalRevenue(totalRevenue)
                .averageFare(averageFare)
                .completionRate(completionRate)
                .build();
    }

    private LocalDateTime parseStartDate(String dateStr) {
        try {
            // Try parsing as full Date-Time (e.g., "2020-01-01T15:30:00")
            return LocalDateTime.parse(dateStr);
        } catch (DateTimeParseException e) {
            // Fallback to Date only (e.g., "2020-01-01") and set to start of day
            return LocalDate.parse(dateStr).atStartOfDay();
        }
    }

    private LocalDateTime parseEndDate(String dateStr) {
        try {
            // Try parsing as full Date-Time
            return LocalDateTime.parse(dateStr);
        } catch (DateTimeParseException e) {
            // Fallback to Date only and set to the very end of the day (23:59:59.999999999)
            return LocalDate.parse(dateStr).atTime(LocalTime.MAX);
        }
    }

    // S3-F5
    @Cacheable(value = "ride-service::S3-F5", key="#key + '-' + #value")
    public List<Ride> findByMetadata(String key, String value) {

        // Validate key and value entered
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Metadata key and value parameters must not be empty"
            );
        }

        return rideRepository.findByMetadataField(key, value);
    }

    // S3-F4
    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F3", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#id"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true),
            @CacheEvict(value = "driver-service::S2-F12", key = "#result.driverId", condition = "#result != null && #result.driverId != null")
    })
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


        if (ride.getDriverId() == null || !rideRepository.driverExists(ride.getDriverId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
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
            ride.setFare(fareEstimate.getEstimatedFare());
        }

        // Update driver status to AVAILABLE
        rideRepository.setDriverAvailable(ride.getDriverId());

        // Create payment record

        try {
            rideRepository.createPayment(
                    ride.getId(),
                    ride.getUserId(),
                    ride.getFare(),
                    LocalDateTime.now()
            );
        } catch (Exception ignored) {}

        // Save ride and return the updated entity
        Ride savedRide = rideRepository.save(ride);
        rideEventPublisher.notifyObservers("RIDE_COMPLETED", buildRidePayload(savedRide));
        return savedRide;

    }

    // S3-F10
    @Cacheable(value = "ride-service::S3-F10", key="#startDate.toString() + '-' + #endDate.toString()")
    public RideAnalyticsDashboardDTO getRideAnalyticsDashboard(LocalDate startDate, LocalDate endDate) {

        if (startDate == null || endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start date and end date parameters are required");
        }

        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Start date must be on or before end date");
        }

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();

        List<Ride> rides = rideRepository.findByRequestedAtBetweenOrderByRequestedAtDesc(start, end);

        long totalRides = rides.size();

        double totalRevenue = rideRepository.getTotalRevenueForCompletedRidesFromPayments(start, end);

        long completedRides = rides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED)
                .count();

        double averageRideFare = completedRides > 0 ? totalRevenue / completedRides : 0.0;

        double completionRate = totalRides > 0
                ? ((double) completedRides / totalRides) * 100.0
                : 0.0;

        Map<RideStatus, Long> ridesByStatus = rides.stream()
                .collect(Collectors.groupingBy(Ride::getStatus, Collectors.counting()));

        return RideAnalyticsDashboardDTO.builder()
                .totalRides(totalRides)
                .totalRevenue(totalRevenue)
                .averageRideFare(averageRideFare)
                .completionRate(completionRate)
                .ridesByStatus(ridesByStatus)
                .build();
    }

    public void logDashboardViewed(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("startDate", startDate.toString());
        payload.put("endDate", endDate.toString());
        payload.put("timestamp", LocalDateTime.now().toString());
        rideEventPublisher.notifyObservers("ANALYTICS_VIEWED", payload);
    }

    private void validateRequiredUpdateKeys(Ride updated) {
        if (updated.getPickupLatitude() == null || updated.getPickupLongitude() == null ||
            updated.getDropoffLatitude() == null || updated.getDropoffLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Location fields (pickup and dropoff latitude/longitude) cannot be null");
        }

        if (updated.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride status cannot be null");
        }
    }

    private Map<String, Object> buildRidePayload(Ride ride) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", ride.getId());
        payload.put("userId", ride.getUserId());
        payload.put("driverId", ride.getDriverId());
        payload.put("status", ride.getStatus() == null ? null : ride.getStatus().name());
        payload.put("fare", ride.getFare());
        payload.put("metadata", ride.getMetadata());
        payload.put("requestedAt", ride.getRequestedAt());
        payload.put("completedAt", ride.getCompletedAt());
        return payload;
    }
}
