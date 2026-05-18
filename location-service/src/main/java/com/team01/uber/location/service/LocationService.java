package com.team01.uber.location.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import java.time.format.DateTimeParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.team01.uber.contracts.dto.LocationDTO;
import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.contracts.events.LocationTrackedEvent;
import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.location.config.LocationEventConfig;
import com.team01.uber.location.adapter.CassandraRowAdapter;
import com.team01.uber.location.adapter.LocationAdapter;
import com.team01.uber.location.client.DriverClient;
import com.team01.uber.location.dto.BatchLocationRequest;
import com.team01.uber.location.dto.BatchLocationResponse;
import com.team01.uber.location.dto.DriverLocationCreateRequest;
import com.team01.uber.location.dto.DriverMovementSummaryDTO;
import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.dto.LocationAnalyticsDTO;
import com.team01.uber.location.dto.NearbyDriverDTO;
import com.team01.uber.location.dto.StationaryDriverDTO;
import com.team01.uber.location.dto.TrackingRequest;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.model.LocationTrackingEvent;
import com.team01.uber.location.model.LocationTrackingEventKey;
import com.team01.uber.location.observer.EntityObserver;
import com.team01.uber.location.repository.LocationRepository;
import com.team01.uber.location.repository.LocationTrackingEventRepository;

import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;

@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);

    private final LocationRepository locationRepository;
    private final LocationTrackingEventRepository trackingRepository;
    private final RedisTemplate redisTemplate;
    private final List<EntityObserver> observers = new CopyOnWriteArrayList<>();
    private final List<EntityObserver> initialObservers;
    private final LocationAdapter locationAdapter = new LocationAdapter();
    private final CassandraRowAdapter cassandraRowAdapter = new CassandraRowAdapter();
    private final DriverClient driverClient;
    private final RabbitTemplate rabbitTemplate;

    @SuppressWarnings("unchecked")
    public LocationService(LocationRepository locationRepository,
                           LocationTrackingEventRepository trackingRepository,
                           RedisTemplate redisTemplate,
                           List<EntityObserver> observers,
                           DriverClient driverClient,
                           RabbitTemplate rabbitTemplate) {
        this.locationRepository = locationRepository;
        this.trackingRepository = trackingRepository;
        this.redisTemplate = redisTemplate;
        this.initialObservers = observers;
        this.driverClient = driverClient;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        if (initialObservers != null) {
            for (EntityObserver observer : initialObservers) {
                register(observer);
            }
        }
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String action, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(action, payload);
        }
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::S4-F1", allEntries = true),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true)
    })
    public Location create(Location location) {
        // Generic PG-only write: does not fire an Observer write to location_events,
        // so it does NOT bust the S4-F10 analytics cache (which is invalidated only
        // by Mongo-touching writes per the cache matrix).
        return locationRepository.save(location);
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::S4-F1", key = "#driverId"),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true),
            @CacheEvict(value = "location-service::S4-F10", allEntries = true)
    })
    public Location createForDriver(Long driverId, DriverLocationCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must not be null");
        }

        driverClient.getDriver(driverId);

        Double latitude = request.getLatitude();
        Double longitude = request.getLongitude();
        if (latitude == null || longitude == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude and longitude are required");
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude or longitude out of valid range");
        }

        Location location = new Location();
        location.setId(null);
        location.setDriverId(driverId);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setMetadata(request.getMetadata());
        location.setTimestamp(LocalDateTime.now());

        return locationRepository.save(location);
    }

    @Cacheable(value = "location-service::location", key = "#id")
    public Location getById(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Error 404"));
    }

    public List<Location> getAll() {
        return locationRepository.findAll();
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::location", key = "#id"),
            @CacheEvict(value = "location-service::S4-F1", allEntries = true),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true),
            @CacheEvict(value = "location-service::S4-F5", allEntries = true),
            @CacheEvict(value = "location-service::S4-F6", allEntries = true),
            @CacheEvict(value = "location-service::S4-F8", allEntries = true),
            @CacheEvict(value = "location-service::S4-F9", allEntries = true),
            @CacheEvict(value = "location-service::S4-F10", allEntries = true)
    })
    public Location update(Long id, Location location) {
        Location existing = getById(id);
        if (location.getDriverId() != null) existing.setDriverId(location.getDriverId());
        if (location.getLatitude() != null) existing.setLatitude(location.getLatitude());
        if (location.getLongitude() != null) existing.setLongitude(location.getLongitude());
        if (location.getTimestamp() != null) existing.setTimestamp(location.getTimestamp());
        if (location.getMetadata() != null) existing.setMetadata(location.getMetadata());
        return locationRepository.save(existing);
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::location", key = "#id"),
            @CacheEvict(value = "location-service::S4-F1", allEntries = true),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true),
            @CacheEvict(value = "location-service::S4-F5", allEntries = true),
            @CacheEvict(value = "location-service::S4-F6", allEntries = true),
            @CacheEvict(value = "location-service::S4-F8", allEntries = true),
            @CacheEvict(value = "location-service::S4-F9", allEntries = true),
            @CacheEvict(value = "location-service::S4-F10", allEntries = true)
    })
    public void delete(Long id) {
        if (!locationRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error 404");
        }
        locationRepository.deleteById(id);
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::S4-F1", key = "#request.driverId"),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true),
            @CacheEvict(value = "location-service::S4-F6", allEntries = true),
            @CacheEvict(value = "location-service::S4-F8", key = "#request.driverId", condition = "#request.driverId != null"),
            @CacheEvict(value = "location-service::S4-F10", allEntries = true)
    })
    @Transactional
    public BatchLocationResponse batchUpdate(BatchLocationRequest request) {
        Long driverId = request.getDriverId();
        if (driverId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "driverId is required");
        }

        List<Location> items = request.getLocations();
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locations must not be null or empty");
        }

        driverClient.getDriver(driverId);

        LocalDateTime base = LocalDateTime.now();
        List<Location> toSave = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            Location item = items.get(i);
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locations must not contain null elements");
            }
            if (item.getLatitude() == null || item.getLatitude() < -90 || item.getLatitude() > 90) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude must be between -90 and 90");
            }
            if (item.getLongitude() == null || item.getLongitude() < -180 || item.getLongitude() > 180) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Longitude must be between -180 and 180");
            }

            Location loc = new Location();
            loc.setDriverId(driverId);
            loc.setLatitude(item.getLatitude());
            loc.setLongitude(item.getLongitude());
            loc.setMetadata(item.getMetadata());
            loc.setTimestamp(base.plusSeconds(i));
            toSave.add(loc);
        }

        locationRepository.saveAll(toSave);
        return new BatchLocationResponse(toSave.size());
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::location", allEntries = true),
            @CacheEvict(value = "location-service::S4-F1", allEntries = true),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true),
            @CacheEvict(value = "location-service::S4-F5", allEntries = true),
            @CacheEvict(value = "location-service::S4-F6", allEntries = true),
            @CacheEvict(value = "location-service::S4-F8", allEntries = true),
            @CacheEvict(value = "location-service::S4-F9", allEntries = true),
            @CacheEvict(value = "location-service::S4-F10", allEntries = true)
    })
    @Transactional
    public long purgeOlderThanDays(int olderThanDays) {
        if (olderThanDays < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "olderThanDays must be non-negative");
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(olderThanDays);
        long count = locationRepository.countOlderThan(cutoff);
        if (count == 0) {
            return 0;
        }

        int deletedRows = locationRepository.deleteOlderThan(cutoff);
        return deletedRows;
    }

    @Cacheable(value = "location-service::S4-F5", key = "#key + ':' + #operator + ':' + #value")
    public List<Location> filterByMetadata(String key, String operator, String value) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must not be blank");
        }
        if (!Set.of("eq", "gt", "lt").contains(operator)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid operator: must be eq, gt, or lt");
        }
        return switch (operator) {
            case "eq" -> locationRepository.findByMetadataKeyEq(key, value);
            case "gt" -> locationRepository.findByMetadataKeyGt(key, value);
            case "lt" -> locationRepository.findByMetadataKeyLt(key, value);
            default -> List.of();
        };
    }

    @Cacheable(value = "location-service::S4-F1", key = "#driverId")
    public Location getLatestByDriverId(Long driverId) {
        driverClient.getDriver(driverId);

        return locationRepository.findTopByDriverIdOrderByTimestampDescIdDesc(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No locations found for driver"));
    }

    public LocationDTO getRecentLocationForDriver(Long driverId) {
        Location latest = locationRepository.findTopByDriverIdOrderByTimestampDescIdDesc(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No location found for driver"));

        if (latest.getTimestamp().isBefore(LocalDateTime.now().minusMinutes(5))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No recent location found for driver (older than 5 minutes)");
        }

        return locationAdapter.adaptToLocationDTO(latest);
    }

    @Cacheable(value = "location-service::S4-F6", key = "#startDate + ':' + #endDate + ':' + #driverId")
    public List<Location> getLocationsInDateRange(String startDate, String endDate, Long driverId) {
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDate.parse(startDate).atStartOfDay();
            end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format. Use YYYY-MM-DD");
        }
        if (driverId != null) {
            return locationRepository.findInDateRangeByDriver(start, end, driverId);
        }
        return locationRepository.findInDateRange(start, end);
    }

    @Cacheable(value = "location-service::S4-F8", key = "#driverId + ':' + #startDate + ':' + #endDate")
    public DriverMovementSummaryDTO getDriverMovementSummary(Long driverId, String startDate, String endDate) {
        // Driver existence check removed per M3 spec §6 (Page 25)

        LocalDateTime start;
        LocalDateTime end;
        try {
            start = startDate.contains("T") ? LocalDateTime.parse(startDate) : LocalDate.parse(startDate).atStartOfDay();
            end = endDate.contains("T") ? LocalDateTime.parse(endDate) : LocalDate.parse(endDate).atTime(LocalTime.MAX);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format. Use YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS");
        }

        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must be on or before endDate");
        }

        List<Object[]> results = locationRepository.getMovementSummary(driverId, start, end);
        Object[] row = results.get(0);

        long totalPoints = ((Number) row[0]).longValue();
        Double avgSpeed = (totalPoints > 0 && row[1] != null) ? ((Number) row[1]).doubleValue() : 0.0;
        Double maxSpeed = row[2] != null ? ((Number) row[2]).doubleValue() : null;
        LocalDateTime firstTs = row[3] != null ? (LocalDateTime) row[3] : null;
        LocalDateTime lastTs  = row[4] != null ? (LocalDateTime) row[4] : null;

        return DriverMovementSummaryDTO.builder()
                .driverId(driverId)
                .totalLocationPoints(totalPoints)
                .averageSpeed(avgSpeed)
                .maxSpeed(maxSpeed)
                .firstTimestamp(firstTs)
                .lastTimestamp(lastTs)
                .build();
    }

    @Cacheable(value = "location-service::S4-F9", key = "#maxSpeed + ':' + #sinceMinutes + ':' + #page + ':' + #size")
    public List<StationaryDriverDTO> findStationaryDrivers(Double maxSpeed, int sinceMinutes, int page, int size) {
        int effectiveSize = Math.min(size, 100);
        LocalDateTime since = LocalDateTime.now().minusMinutes(sinceMinutes);
        List<Object[]> results = locationRepository.findStationaryDriversLocal(maxSpeed, since);

        List<StationaryDriverDTO> stationaryDrivers = new ArrayList<>();
        for (Object[] row : results) {
            Long driverId = ((Number) row[0]).longValue();
            MDC.put("driverId", String.valueOf(driverId));
            try {
                DriverDTO driver = driverClient.getDriver(driverId);
                stationaryDrivers.add(StationaryDriverDTO.builder()
                        .driverId(driverId)
                        .driverName(driver.name())
                        .latitude((Double) row[1])
                        .longitude((Double) row[2])
                        .lastSpeed(row[3] != null ? ((Number) row[3]).doubleValue() : null)
                        .lastUpdated((LocalDateTime) row[4])
                        .build());
            } catch (Exception e) {
                log.warn("Feign call to driver-service failed for driverId={}: {}", driverId, e.getMessage(), e);
            } finally {
                MDC.remove("driverId");
            }
        }

        int fromIndex = page * effectiveSize;
        if (fromIndex >= stationaryDrivers.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + effectiveSize, stationaryDrivers.size());
        return stationaryDrivers.subList(fromIndex, toIndex);
    }

    @Cacheable(value = "location-service::S4-F3", key = "#lat + ':' + #lon + ':' + #radiusKm + ':' + #page + ':' + #size")
    public List<NearbyDriverDTO> findNearbyDrivers(Double lat, Double lon, Double radiusKm, int page, int size) {
        int effectiveSize = Math.min(size, 100);
        List<Object[]> results = locationRepository.findNearbyDriversLocal(lat, lon, radiusKm);

        List<NearbyDriverDTO> nearbyDrivers = new ArrayList<>();
        for (Object[] row : results) {
            Long driverId = ((Number) row[0]).longValue();
            MDC.put("driverId", String.valueOf(driverId));
            try {
                DriverDTO driver = driverClient.getDriver(driverId);
                if ("AVAILABLE".equals(driver.status())) {
                    nearbyDrivers.add(NearbyDriverDTO.builder()
                            .driverId(driverId)
                            .driverName(driver.name())
                            .latitude((Double) row[1])
                            .longitude((Double) row[2])
                            .distanceKm((Double) row[3])
                            .build());
                }
            } catch (Exception e) {
                log.warn("Feign call to driver-service failed for driverId={}: {}", driverId, e.getMessage(), e);
            } finally {
                MDC.remove("driverId");
            }
        }

        int fromIndex = page * effectiveSize;
        if (fromIndex >= nearbyDrivers.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + effectiveSize, nearbyDrivers.size());
        return nearbyDrivers.subList(fromIndex, toIndex);
    }

    @Cacheable(value = "location-service::S4-F12",
               key = "#driverId + ':' + (#startTime == null ? '' : #startTime) + ':' + (#endTime == null ? '' : #endTime)")
    public List<LocationTrackingDTO> getTrackingTimeline(Long driverId, String startTime, String endTime) {
        driverClient.getDriver(driverId);

        List<LocationTrackingEvent> events;
        // Guard: Skip filter if either param is null or blank (A6-F12 fix)
        if (startTime != null && !startTime.isBlank() && endTime != null && !endTime.isBlank()) {
            try {
                Instant start = parseToInstant(startTime, true);
                Instant end = parseToInstant(endTime, false);
                events = trackingRepository.findByDriverIdAndTimestampBetween(driverId, start, end);
            } catch (Exception e) {
                // Reject invalid formats with 400
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format: " + e.getMessage());
            }
        } else {
            // Default to all events for the driver if range is incomplete
            events = trackingRepository.findByKeyDriverId(driverId);
        }

        return events.stream()
                .map(cassandraRowAdapter::adapt)
                .toList();
    }

    public void handleRidePlaced(RidePlacedEvent event) {
        String idempotencyKey = "idempotency:location-service:ride.placed:" + event.rideId();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "PROCESSED", java.time.Duration.ofHours(24));
        if (Boolean.FALSE.equals(isNew)) {
            log.info("ride.placed idempotency: rideId={} already processed, skipping", event.rideId());
            return;
        }

        MDC.put("driverId", String.valueOf(event.driverId()));
        MDC.put("rideId",   String.valueOf(event.rideId()));
        try {
            log.info("Consuming ride.placed for driverId={}", event.driverId());
            Map<String, Object> payload = new HashMap<>();
            payload.put("driverId", event.driverId());
            payload.put("rideId",   event.rideId());
            payload.put("action",   "RIDE_PLACED");
            notifyObservers("LOCATION_UPDATED", payload);
            log.info("Processed ride.placed for driverId={}", event.driverId());
        } finally {
            MDC.remove("driverId");
            MDC.remove("rideId");
        }
    }

    @CacheEvict(value = "location-service::S4-F12", allEntries = true)
    public void handleRideCompleted(RideCompletedEvent event) {
        MDC.put("driverId", String.valueOf(event.driverId()));
        MDC.put("rideId",   String.valueOf(event.rideId()));
        try {
            log.info("Consuming ride.completed for driverId={}", event.driverId());
            java.util.Optional<LocationTrackingEvent> latestOpt = trackingRepository.findTopByKeyDriverId(event.driverId());
            if (latestOpt.isPresent()) {
                LocationTrackingEvent latest = latestOpt.get();
                if (event.rideId().equals(latest.getRideId())) {
                    log.info("ride.completed idempotency: rideId={} already marked on latest ping, skipping", event.rideId());
                    return;
                }
                latest.setRideId(event.rideId());
                trackingRepository.save(latest);
                log.info("Processed ride.completed: marked final ping with rideId={} for driverId={}", event.rideId(), event.driverId());
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("driverId", event.driverId());
            payload.put("rideId",   event.rideId());
            notifyObservers("TRIP_COMPLETED", payload);
        } finally {
            MDC.remove("driverId");
            MDC.remove("rideId");
        }
    }

    public void handleRideCancelled(RideCancelledEvent event) {
        String idempotencyKey = "idempotency:location-service:ride.cancelled:" + event.rideId();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "PROCESSED", java.time.Duration.ofHours(24));
        if (Boolean.FALSE.equals(isNew)) {
            log.info("ride.cancelled idempotency: rideId={} already processed, skipping", event.rideId());
            return;
        }

        MDC.put("driverId", String.valueOf(event.driverId()));
        MDC.put("rideId",   String.valueOf(event.rideId()));
        try {
            log.info("Consuming ride.cancelled for driverId={}", event.driverId());
            Map<String, Object> payload = new HashMap<>();
            payload.put("driverId", event.driverId());
            payload.put("rideId",   event.rideId());
            payload.put("reason",   event.reason());
            notifyObservers("TRIP_CANCELLED", payload);
            log.info("Processed ride.cancelled for driverId={}: logged TRIP_CANCELLED to Mongo", event.driverId());
        } finally {
            MDC.remove("driverId");
            MDC.remove("rideId");
        }
    }

    private Instant parseToInstant(String dateStr, boolean startOfDay) {
        try {
            if (dateStr.contains("T")) {
                if (dateStr.endsWith("Z")) {
                    return Instant.parse(dateStr);
                }
                return LocalDateTime.parse(dateStr).toInstant(java.time.ZoneOffset.UTC);
            }
            LocalDate date = LocalDate.parse(dateStr);
            LocalDateTime dateTime = startOfDay ? date.atStartOfDay() : date.atTime(LocalTime.MAX);
            return dateTime.toInstant(java.time.ZoneOffset.UTC);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unsupported date format: " + dateStr);
        }
    }

    @Cacheable(value = "location-service::S4-F10", key = "#startDate + ':' + #endDate")
    public LocationAnalyticsDTO getAnalytics(String startDate, String endDate) {
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = startDate.contains("T") ? LocalDateTime.parse(startDate) : LocalDate.parse(startDate).atStartOfDay();
            end = endDate.contains("T") ? LocalDateTime.parse(endDate) : LocalDate.parse(endDate).atTime(LocalTime.MAX);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format. Use YYYY-MM-DD or YYYY-MM-DDTHH:MM:SS");
        }

        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate cannot be after endDate");
        }

        List<Object[]> statsResults = locationRepository.getDashboardStats(start, end);
        List<Object[]> hourlyResults = locationRepository.getEventsByHour(start, end);

        if (statsResults.isEmpty() || statsResults.get(0)[0] == null) {
            // Return empty analytics if no data found
            return LocationAnalyticsDTO.builder()
                    .totalLocationEvents(0L)
                    .activeDrivers(0L)
                    .averageSpeed(0.0)
                    .eventsByHour(new java.util.HashMap<>())
                    .build();
        }

        return locationAdapter.adaptToLocationAnalytics(statsResults.get(0), hourlyResults);
    }

    @Caching(evict = {
            @CacheEvict(value = "location-service::S4-F1", key = "#driverId"),
            @CacheEvict(value = "location-service::S4-F3", allEntries = true),
            @CacheEvict(value = "location-service::S4-F10", allEntries = true),
            @CacheEvict(value = "location-service::S4-F12", allEntries = true)
    })
    public LocationTrackingDTO recordGpsEvent(Long driverId, TrackingRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must not be null");
        }

        driverClient.getDriver(driverId);

        if (request.getLatitude() == null || request.getLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude and longitude are required");
        }
        if (request.getLatitude() < -90 || request.getLatitude() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude must be between -90 and 90");
        }
        if (request.getLongitude() < -180 || request.getLongitude() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Longitude must be between -180 and 180");
        }

        Instant now = Instant.now();

        LocationTrackingEvent event = new LocationTrackingEvent();
        event.setDriverId(driverId);
        event.setTimestamp(now);
        event.setLatitude(request.getLatitude());
        event.setLongitude(request.getLongitude());
        event.setSpeed(request.getSpeed());
        event.setHeading(request.getHeading());
        event.setAccuracy(request.getAccuracy());
        event.setRideId(request.getRideId());
        event.setNotes(request.getNotes());

        trackingRepository.save(event);

        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driverId);
        payload.put("latitude", request.getLatitude());
        payload.put("longitude", request.getLongitude());
        payload.put("speed", request.getSpeed());
        payload.put("heading", request.getHeading());
        payload.put("accuracy", request.getAccuracy());
        payload.put("rideId", request.getRideId());
        payload.put("notes", request.getNotes());
        payload.put("timestamp", now);
        notifyObservers("TRACKING_RECORDED", payload);

        MDC.put("routingKey", LocationEventConfig.ROUTING_KEY_TRACKED);
        try {
            LocationTrackedEvent trackedEvent = new LocationTrackedEvent(
                    driverId,
                    request.getRideId(),
                    request.getLatitude(),
                    request.getLongitude()
            );
            rabbitTemplate.convertAndSend(
                    LocationEventConfig.LOCATION_EXCHANGE,
                    LocationEventConfig.ROUTING_KEY_TRACKED,
                    trackedEvent
            );
            log.info("Published {} for driverId={}", LocationEventConfig.ROUTING_KEY_TRACKED, driverId);
        } catch (Exception e) {
            log.warn("Failed to publish {}: {}", LocationEventConfig.ROUTING_KEY_TRACKED, e.getMessage(), e);
        } finally {
            MDC.remove("routingKey");
        }

        return locationAdapter.adaptToLocationTrackingDTO(event);
    }
}
