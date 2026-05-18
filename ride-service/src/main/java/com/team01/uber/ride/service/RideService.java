package com.team01.uber.ride.service;

import com.team01.uber.contracts.dto.DriverAvailabilityDTO;
import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.dto.DriverRideSummaryDTO;
import com.team01.uber.contracts.dto.RideSummaryDTO;
import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.ride.client.DriverClient;
import com.team01.uber.ride.client.LocationClient;
import com.team01.uber.ride.client.UserClient;
import com.team01.uber.ride.dto.*;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.enums.RideStopStatus;
import com.team01.uber.ride.model.DriverNode;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.model.RideStop;
import com.team01.uber.ride.model.RodeWithRelationship;
import com.team01.uber.ride.model.UserNode;
import com.team01.uber.ride.messaging.publishers.RideEventPublisherService;
import com.team01.uber.ride.observer.RideEventPublisher;
import com.team01.uber.ride.repository.DriverNodeRepository;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import com.team01.uber.ride.repository.UserNodeRepository;
import feign.FeignException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
import java.util.stream.Collectors;

@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    private final RideRepository rideRepository;
    private final RideStopRepository rideStopRepository;
    private final UserNodeRepository userNodeRepository;
    private final DriverNodeRepository driverNodeRepository;
    private final RideEventPublisher rideEventPublisher;
    private final DriverClient driverClient;
    private final UserClient userClient;
    private final LocationClient locationClient;
    private final RideEventPublisherService producer;

    public RideService(RideRepository rideRepository,
            RideStopRepository rideStopRepository,
            UserNodeRepository userNodeRepository,
            DriverNodeRepository driverNodeRepository,
            RideEventPublisher rideEventPublisher,
            DriverClient driverClient,
            UserClient userClient,
            LocationClient locationClient,
            RideEventPublisherService producer) {
        this.rideRepository = rideRepository;
        this.rideStopRepository = rideStopRepository;
        this.userNodeRepository = userNodeRepository;
        this.driverNodeRepository = driverNodeRepository;
        this.rideEventPublisher = rideEventPublisher;
        this.driverClient = driverClient;
        this.userClient = userClient;
        this.locationClient = locationClient;
        this.producer = producer;
    }

    // ── M1/M2 methods (unchanged logic, cross-service SQL replaced) ───────────

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

    @Cacheable(value = "ride-service::ride", key = "#id")
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
    @Cacheable(value = "ride-service::S3-F9", key = "#rideId")
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

    // S3-F7 — M3: removed direct driver SQL; publishes ride.cancelled event (§8.4)
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only REQUESTED or ACCEPTED rides can be cancelled");
        }

        ride.setStatus(RideStatus.CANCELLED);
        Ride savedRide = rideRepository.save(ride);
        // M3: driver-service and payment-service consume ride.cancelled (§8.4)
        rideEventPublisher.notifyObservers("RIDE_CANCELLED", buildRidePayload(savedRide));
        producer.publishRideCancelled(savedRide, "user_requested");
        return savedRide;
    }

    // S3-F1
    @Cacheable(value = "ride-service::S3-F1", key = "T(java.util.Objects).toString(#status, '') + '-' + T(java.util.Objects).toString(#startDate, '') + '-' + T(java.util.Objects).toString(#endDate, '') + '-' + T(java.util.Objects).toString(#userId, '')")
    public List<Ride> searchRides(RideStatus status, LocalDate startDate, LocalDate endDate, Long userId) {
        LocalDateTime start = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime end = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        return rideRepository.searchRidesFlexible(status == null ? null : status.name(), start, end, userId);
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

    // S3-F2 — M3: Feign replaces cross-service SQL; publishes ride.placed (§5
    // S3-F2)
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only rides with status REQUESTED can be assigned a driver");
        }

        log.info("Calling driverClient.getDriver with args={}", driverId);
        DriverDTO driver = driverClient.getDriver(driverId);
        log.info("driverClient.getDriver returned successfully");

        if (!"AVAILABLE".equals(driver.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Driver is not available");
        }

        ride.setDriverId(driverId);
        ride.setStatus(RideStatus.ACCEPTED);
        Ride savedRide = rideRepository.save(ride);
        // M3: ride.placed event → driver-service sets driver BUSY (§5 S3-F2)
        rideEventPublisher.notifyObservers("RIDE_DRIVER_ASSIGNED", buildRidePayload(savedRide));
        producer.publishRidePlaced(savedRide);
        return savedRide;
    }

    // S3-F3
    @Cacheable(value = "ride-service::S3-F3", key = "#request.pickupLatitude + '-' + #request.pickupLongitude + '-' + #request.dropoffLatitude + '-' + #request.dropoffLongitude")
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
        if (activeRides > 20)
            surgeMultiplier = 2.0;
        else if (activeRides > 10)
            surgeMultiplier = 1.5;
        else
            surgeMultiplier = 1.0;

        double fare = 15.0 * distance * surgeMultiplier;

        return FareEstimateDTO.builder()
                .estimatedDistance(distance)
                .estimatedDuration(duration)
                .estimatedFare(fare)
                .surgeMultiplier(surgeMultiplier)
                .build();
    }

    // S3-F6
    @Cacheable(value = "ride-service::S3-F6", key = "#startDateStr + '-' + #endDateStr")
    public RideAnalyticsDTO getRideAnalytics(String startDateStr, String endDateStr) {
        LocalDateTime start = parseStartDate(startDateStr);
        LocalDateTime end = parseEndDate(endDateStr);
        List<Ride> rides = rideRepository.findByRequestedAtBetweenOrderByRequestedAtDesc(start, end);

        long totalRides = rides.size();
        long completedRides = rides.stream().filter(r -> r.getStatus() == RideStatus.COMPLETED).count();
        long cancelledRides = rides.stream().filter(r -> r.getStatus() == RideStatus.CANCELLED).count();
        double totalRevenue = rides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED && r.getFare() != null)
                .mapToDouble(Ride::getFare).sum();
        double averageFare = completedRides > 0 ? totalRevenue / completedRides : 0.0;
        double completionRate = totalRides > 0 ? (double) completedRides / totalRides : 0.0;

        return RideAnalyticsDTO.builder()
                .totalRides(totalRides)
                .completedRides(completedRides)
                .cancelledRides(cancelledRides)
                .totalRevenue(totalRevenue)
                .averageFare(averageFare)
                .completionRate(completionRate)
                .build();
    }

    // S3-F5
    @Cacheable(value = "ride-service::S3-F5", key = "#key + '-' + #value")
    public List<Ride> findByMetadata(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Metadata key and value parameters must not be empty");
        }
        return rideRepository.findByMetadataField(key, value);
    }

    // S3-F4 — M3: Feign pre-checks + publishes ride.completed (§8.3)
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

        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only rides with IN_PROGRESS status can be completed. Current status: " + ride.getStatus());
        }

        // Calculate fare locally if not already set
        if (ride.getFare() == null) {
            FareEstimateRequestDTO fareRequest = new FareEstimateRequestDTO(
                    ride.getPickupLatitude(), ride.getPickupLongitude(),
                    ride.getDropoffLatitude(), ride.getDropoffLongitude());
            ride.setFare(estimateFare(fareRequest).getEstimatedFare());
        }

        // Pre-saga Feign check 1: user must be ACTIVE (§8.3)
        log.info("Calling userClient.getUser with args={}", ride.getUserId());
        UserDTO user = userClient.getUser(ride.getUserId());
        log.info("userClient.getUser returned successfully");
        if (!"ACTIVE".equals(user.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User account is not active");
        }

        // Pre-saga Feign check 2: driver must be BUSY (§8.3)
        log.info("Calling driverClient.getDriverAvailability with args={}", ride.getDriverId());
        DriverAvailabilityDTO availability = driverClient.getDriverAvailability(ride.getDriverId());
        log.info("driverClient.getDriverAvailability returned successfully");
        if (!"BUSY".equals(availability.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Driver is not currently active for this ride");
        }

        // Pre-saga Feign check 3: driver must have recent GPS ping (§8.3)
        log.info("Calling locationClient.getRecentLocationForDriver with args={}", ride.getDriverId());
        locationClient.getRecentLocationForDriver(ride.getDriverId());
        log.info("locationClient.getRecentLocationForDriver returned successfully");

        // Commit local state then publish (§2.11 publish-after-commit)
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(LocalDateTime.now());
        Ride savedRide = rideRepository.save(ride);
        // M3: payment-service creates PENDING payment; driver-service sets AVAILABLE
        // (§8.3)
        rideEventPublisher.notifyObservers("RIDE_COMPLETED", buildRidePayload(savedRide));
        producer.publishRideCompleted(savedRide);
        return savedRide;
    }

    // S3-F10 — M3: local rides.fare replaces cross-service payments JOIN (§5
    // S3-F10)
    @Cacheable(value = "ride-service::S3-F10", key = "#startDate.toString() + '-' + #endDate.toString()")
    public RideAnalyticsDashboardDTO getRideAnalyticsDashboard(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start date and end date parameters are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start date must be on or before end date");
        }

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<Ride> rides = rideRepository.findByRequestedAtBetweenOrderByRequestedAtDesc(start, end);

        long totalRides = rides.size();
        long completedRides = rides.stream().filter(r -> r.getStatus() == RideStatus.COMPLETED).count();

        // M3: local sum replaces getTotalRevenueForCompletedRidesFromPayments()
        double totalRevenue = rides.stream()
                .filter(r -> r.getStatus() == RideStatus.COMPLETED && r.getFare() != null)
                .mapToDouble(Ride::getFare)
                .sum();

        double averageRideFare = completedRides > 0 ? totalRevenue / completedRides : 0.0;
        double completionRate = totalRides > 0 ? ((double) completedRides / totalRides) : 0.0;

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

    // S3-F11 — M3: Feign replaces cross-service SQL (§5 S3-F11)
    @CacheEvict(value = "ride-service::S3-F12", allEntries = true)
    public String recordInteraction(Long rideId) {
        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));

        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only COMPLETED rides can have interactions recorded. Current status: " + ride.getStatus());
        }

        Long userId = ride.getUserId();
        Long driverId = ride.getDriverId();

        if (driverId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride has no assigned driver");
        }

        // Idempotency check — unchanged from M2
        Optional<UserNode> userNodeOpt = userNodeRepository.findById(userId);
        if (userNodeOpt.isPresent()) {
            List<RodeWithRelationship> rels = userNodeOpt.get().getRodeWithRelationships();
            if (rels != null) {
                for (RodeWithRelationship rel : rels) {
                    if (rel.getDriver() != null
                            && rel.getDriver().getDriverId().equals(driverId)
                            && rel.getRecordedRideIds() != null
                            && rel.getRecordedRideIds().contains(rideId)) {
                        return "Interaction already recorded (idempotent)";
                    }
                }
            }
        }

        // M3: Feign → user-service replaces findUserNameById()
        log.info("Calling userClient.getUser with args={}", userId);
        String userName;
        try {
            UserDTO user = userClient.getUser(userId);
            log.info("userClient.getUser returned successfully");
            userName = user.name();
        } catch (Exception e) {
            log.warn("Resilient call to user-service failed: {}", e.getMessage());
            userName = "Unknown";
        }

        // M3: Feign → driver-service replaces findDriverNameById() +
        // findDriverVehicleTypeById()
        log.info("Calling driverClient.getDriver with args={}", driverId);
        String driverName;
        String vehicleType;
        try {
            DriverDTO driver = driverClient.getDriver(driverId);
            log.info("driverClient.getDriver returned successfully");
            driverName = driver.name();
            vehicleType = (String) driver.vehicleDetails().getOrDefault("vehicleType", "");
        } catch (Exception e) {
            log.warn("Resilient call to driver-service failed: {}", e.getMessage());
            driverName = "Unknown";
            vehicleType = "";
        }

        // Make effectively final for lambda use
        final String finalDriverName = driverName;
        final String finalVehicleType = vehicleType;

        // Neo4j write — unchanged from M2
        UserNode userNode = userNodeRepository.findById(userId)
                .orElse(new UserNode(userId, userName, new ArrayList<>()));

        DriverNode driverNode = driverNodeRepository.findById(driverId)
                .orElseGet(() -> {
                    DriverNode dn = new DriverNode(driverId, finalDriverName, finalVehicleType);
                    return driverNodeRepository.save(dn);
                });

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

        Map<String, Object> payload = new HashMap<>();
        payload.put("rideId", rideId);
        payload.put("userId", userId);
        payload.put("driverId", driverId);
        rideEventPublisher.notifyObservers("INTERACTION_RECORDED", payload);

        return "Interaction recorded successfully";
    }

    // ── M3 new methods for exposed endpoints (S3-READ-DB) ────────────────────

    // GET /api/rides/user/{userId}/summary — called by S1-F3 (§5)
    public RideSummaryDTO getUserRideSummary(Long userId) {
        List<RideStatus> completedStatuses = List.of(RideStatus.COMPLETED, RideStatus.PAID);
        List<RideStatus> cancelledStatuses = List.of(RideStatus.CANCELLED);

        long totalRides = rideRepository.countTotalRidesByUserId(userId);
        long completedRides = rideRepository.countRidesByUserIdAndStatuses(userId, completedStatuses);
        long cancelledRides = rideRepository.countRidesByUserIdAndStatuses(userId, cancelledStatuses);
        Double totalSpent = rideRepository.sumFareByUserIdAndStatuses(userId, completedStatuses);
        if (totalSpent == null)
            totalSpent = 0.0;
        double averageFare = completedRides > 0 ? totalSpent / completedRides : 0.0;

        return new RideSummaryDTO(userId, totalRides, completedRides, cancelledRides, totalSpent, averageFare);
    }

    // GET /api/rides/user/{userId}/active-count — called by S1-F4 (§5)
    public int getActiveRideCountForUser(Long userId) {
        List<RideStatus> activeStatuses = List.of(
                RideStatus.REQUESTED, RideStatus.ACCEPTED,
                RideStatus.IN_PROGRESS, RideStatus.PAYMENT_PENDING);
        return rideRepository.countActiveRidesByUserId(userId, activeStatuses);
    }

    // GET /api/rides/user/{userId}/completed-count — called by S1-F9 (§5)
    public long getCompletedRideCountForUser(Long userId) {
        List<RideStatus> completedStatuses = List.of(RideStatus.COMPLETED, RideStatus.PAID);
        return rideRepository.countCompletedRidesByUserId(userId, completedStatuses);
    }

    // GET /api/rides/driver/{driverId}/summary — called by S2-F3, S2-F12 (§5)
    public DriverRideSummaryDTO getDriverRideSummary(Long driverId, String startDate, String endDate) {
        List<RideStatus> completedStatuses = List.of(RideStatus.COMPLETED, RideStatus.PAID);
        List<RideStatus> allStatuses = new ArrayList<>(EnumSet.allOf(RideStatus.class));

        long totalRides;
        long completedRides;
        Double totalEarnings;

        if (startDate != null && !startDate.isBlank()
                && endDate != null && !endDate.isBlank()) {
            // WITH date range — use dedicated query (no null parameter issue)
            LocalDateTime start = parseStartDate(startDate);
            LocalDateTime end = parseEndDate(endDate);
            totalRides = rideRepository.countRidesByDriverIdAndStatusesAndDateRange(driverId, allStatuses, start, end);
            completedRides = rideRepository.countRidesByDriverIdAndStatusesAndDateRange(driverId, completedStatuses,
                    start, end);
            totalEarnings = rideRepository.sumFareByDriverIdAndStatusesAndDateRange(driverId, completedStatuses, start,
                    end);
        } else {
            // NO date range — use simple query
            totalRides = rideRepository.countRidesByDriverIdAndStatuses(driverId, allStatuses);
            completedRides = rideRepository.countRidesByDriverIdAndStatuses(driverId, completedStatuses);
            totalEarnings = rideRepository.sumFareByDriverIdAndStatuses(driverId, completedStatuses);
        }

        if (totalEarnings == null)
            totalEarnings = 0.0;
        double averageFare = completedRides > 0 ? totalEarnings / completedRides : 0.0;

        return new DriverRideSummaryDTO(
                driverId,
                totalRides,
                completedRides,
                totalEarnings,
                averageFare
        );
    }

    // GET /api/rides/driver/{driverId}/active-count — called by S2-F4 (§5)
    public int getActiveRideCountForDriver(Long driverId) {
        List<RideStatus> activeStatuses = List.of(
                RideStatus.ACCEPTED, RideStatus.IN_PROGRESS,
                RideStatus.PAYMENT_PENDING);
        return rideRepository.countActiveRidesByDriverId(driverId, activeStatuses);
    }

    // GET /api/rides/driver/{driverId}/completed-count — called by S2-F6 (§5)
    public long getCompletedRideCountForDriver(Long driverId) {
        List<RideStatus> completedStatuses = List.of(RideStatus.COMPLETED, RideStatus.PAID);
        return rideRepository.countCompletedRidesByDriverId(driverId, completedStatuses);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LocalDateTime parseStartDate(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(dateStr).atStartOfDay();
        }
    }

    private LocalDateTime parseEndDate(String dateStr) {
        try {
            return LocalDateTime.parse(dateStr);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(dateStr).atTime(LocalTime.MAX);
        }
    }

    private void validateRequiredUpdateKeys(Ride updated) {
        if (updated.getPickupLatitude() == null || updated.getPickupLongitude() == null ||
                updated.getDropoffLatitude() == null || updated.getDropoffLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Location fields (pickup and dropoff latitude/longitude) cannot be null");
        }
        if (updated.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride status cannot be null");
        }
    }

    // ── Saga consumer state updates ───────────────────────────────────────────

    // ── Saga consumer state updates ───────────────────────────────────────────

    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    @Transactional
    public Ride markRideStatus(Long rideId, RideStatus newStatus) {
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null || ride.getStatus() == newStatus)
            return null;
        ride.setStatus(newStatus);
        return rideRepository.save(ride);
    }

    /**
     * Saga-safe state transition. Only mutates the ride when its current status is in {@code allowedFrom}.
     *
     * @return saved Ride if the transition fired; null if the ride was not found, was already at
     *         {@code newStatus} (idempotent duplicate), or was in a state not present in {@code allowedFrom}
     *         (out-of-order event — caller MUST NOT cascade side-effects in that case).
     */
    @Caching(evict = {
            @CacheEvict(value = "ride-service::ride", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F1", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F6", allEntries = true),
            @CacheEvict(value = "ride-service::S3-F9", key = "#rideId"),
            @CacheEvict(value = "ride-service::S3-F10", allEntries = true)
    })
    @Transactional
    public Ride transitionRideStatus(Long rideId, java.util.EnumSet<RideStatus> allowedFrom, RideStatus newStatus) {
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null) {
            log.warn("transitionRideStatus: ride {} not found (target={})", rideId, newStatus);
            return null;
        }
        if (ride.getStatus() == newStatus) {
            log.info("transitionRideStatus: ride {} already at {} (idempotent duplicate)", rideId, newStatus);
            return null;
        }
        if (!allowedFrom.contains(ride.getStatus())) {
            log.warn("transitionRideStatus: ride {} in {} cannot transition to {} (allowed={})",
                    rideId, ride.getStatus(), newStatus, allowedFrom);
            return null;
        }
        ride.setStatus(newStatus);
        return rideRepository.save(ride);
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
