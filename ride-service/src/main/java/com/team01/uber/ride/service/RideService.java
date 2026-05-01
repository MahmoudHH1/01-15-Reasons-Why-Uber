package com.team01.uber.ride.service;

import com.team01.uber.ride.dto.FareEstimateDTO;
import com.team01.uber.ride.dto.FareEstimateRequestDTO;
import com.team01.uber.ride.dto.RideAnalyticsDTO;
import com.team01.uber.ride.dto.RideDetailsDTO;
import com.team01.uber.ride.dto.StopDetailDTO;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.DriverNode;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.model.RodeWithRelationship;
import com.team01.uber.ride.model.UserNode;
import com.team01.uber.ride.observer.RideEventPublisher;
import com.team01.uber.ride.repository.DriverNodeRepository;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import com.team01.uber.ride.repository.UserNodeRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


@Service
public class RideService {

    private final RideRepository rideRepository;
    private final RideStopRepository rideStopRepository;
    private final UserNodeRepository userNodeRepository;
    private final DriverNodeRepository driverNodeRepository;
    private final RideEventPublisher rideEventPublisher;

    public RideService(RideRepository rideRepository,
                       RideStopRepository rideStopRepository,
                       UserNodeRepository userNodeRepository,
                       DriverNodeRepository driverNodeRepository,
                       RideEventPublisher rideEventPublisher) {
        this.rideRepository = rideRepository;
        this.rideStopRepository = rideStopRepository;
        this.userNodeRepository = userNodeRepository;
        this.driverNodeRepository = driverNodeRepository;
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
        return rideRepository.save(ride);
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

        return rideRepository.save(existing);
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
        return rideRepository.save(ride);
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
        if (!rideRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        rideRepository.deleteById(id);
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
        rideRepository.save(ride);

        if(rideRepository.setDriverBusy(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to set driver status to BUSY. Driver may have become unavailable.");
        }

        return ride;
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
        return rideRepository.save(ride);

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

    // S3-F11: Record User-Driver Riding Pattern
    @CacheEvict(value = "ride-service::S3-F12", allEntries = true)
    public String recordInteraction(Long rideId) {
        // Find ride in PostgreSQL — 404 if not found
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));

        // Verify ride is COMPLETED — 400 otherwise
        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only COMPLETED rides can have interactions recorded. Current status: " + ride.getStatus());
        }

        Long userId = ride.getUserId();
        Long driverId = ride.getDriverId();

        if (driverId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride has no assigned driver");
        }

        // Idempotency check: look for this rideId in existing Neo4j RODE_WITH edges
        Optional<UserNode> userNodeOpt = userNodeRepository.findById(userId);
        if (userNodeOpt.isPresent()) {
            List<RodeWithRelationship> rels = userNodeOpt.get().getRodeWithRelationships();
            if (rels != null) {
                for (RodeWithRelationship rel : rels) {
                    if (rel.getDriver() != null
                            && rel.getDriver().getDriverId().equals(driverId)
                            && rel.getRecordedRideIds() != null
                            && rel.getRecordedRideIds().contains(rideId)) {
                        // Already recorded — idempotent short-circuit, no observer event
                        return "Interaction already recorded (idempotent)";
                    }
                }
            }
        }

        // Cross-service SQL lookups for user/driver names (shared PG, no HTTP calls)
        String userName = rideRepository.findUserNameById(userId);
        String driverName = rideRepository.findDriverNameById(driverId);
        String vehicleType = rideRepository.findDriverVehicleTypeById(driverId);

        // Find or create UserNode and DriverNode in Neo4j
        UserNode userNode = userNodeRepository.findById(userId)
                .orElse(new UserNode(userId, userName != null ? userName : "Unknown", new ArrayList<>()));

        DriverNode driverNode = driverNodeRepository.findById(driverId)
                .orElseGet(() -> {
                    DriverNode dn = new DriverNode(driverId,
                            driverName != null ? driverName : "Unknown",
                            vehicleType != null ? vehicleType : "");
                    return driverNodeRepository.save(dn);
                });

        // Find or create the RODE_WITH relationship, incrementing rideCount
        List<RodeWithRelationship> relationships = userNode.getRodeWithRelationships();
        if (relationships == null) {
            relationships = new ArrayList<>();
            userNode.setRodeWithRelationships(relationships);
        }

        RodeWithRelationship existingRel = relationships.stream()
                .filter(r -> r.getDriver() != null && r.getDriver().getDriverId().equals(driverId))
                .findFirst()
                .orElse(null);

        if (existingRel != null) {
            existingRel.setRideCount(existingRel.getRideCount() + 1);
            existingRel.setLastRideDate(LocalDateTime.now());
            if (existingRel.getRecordedRideIds() == null) {
                existingRel.setRecordedRideIds(new ArrayList<>());
            }
            existingRel.getRecordedRideIds().add(rideId);
        } else {
            List<Long> recordedIds = new ArrayList<>();
            recordedIds.add(rideId);
            relationships.add(new RodeWithRelationship(null, driverNode, 1, LocalDateTime.now(), recordedIds));
        }

        userNodeRepository.save(userNode);

        // Log INTERACTION_RECORDED event via Observer (non-idempotent path only)
        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", rideId);
        payload.put("userId", userId);
        payload.put("driverId", driverId);
        rideEventPublisher.notifyObservers("INTERACTION_RECORDED", payload);

        return "Interaction recorded successfully";
    }
}