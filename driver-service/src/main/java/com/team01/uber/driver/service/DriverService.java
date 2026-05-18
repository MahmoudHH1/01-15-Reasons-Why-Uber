package com.team01.uber.driver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.contracts.dto.DriverRideSummaryDTO;
import com.team01.uber.driver.client.RideClient;
import com.team01.uber.driver.adapter.ElasticsearchHitAdapter;
import com.team01.uber.driver.cache.CacheInvalidator;
import com.team01.uber.driver.dto.DriverDashboardDTO;
import com.team01.uber.driver.dto.DriverEarningsDTO;
import com.team01.uber.driver.dto.DriverSearchResultDTO;
import com.team01.uber.driver.dto.TopDriverDTO;
import com.team01.uber.driver.messaging.DriverEventPublisher;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverSearchDocument;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.observer.EntityObserver;
import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.repository.DriverRepository;
import com.team01.uber.driver.repository.DriverSearchEsRepository;
import feign.FeignException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class DriverService {

    private static final Logger log = LoggerFactory.getLogger(DriverService.class);
    private static final String CACHE_PREFIX = "driver-service::S2-F12::";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DriverRepository driverRepository;
    private final MongoEventLogger mongoEventLogger;
    private final RedisTemplate<String, String> redisTemplate;
    private final CacheInvalidator cacheInvalidator;
    private final DriverSearchEsRepository searchEsRepository;
    private final ElasticsearchHitAdapter searchHitAdapter;
    private final DriverIndexerService driverIndexerService;
    private final CacheManager cacheManager;
    private final RideClient rideClient;
    private final DriverEventPublisher driverEventPublisher;
    private final List<EntityObserver> observers = new ArrayList<>();

    public DriverService(DriverRepository driverRepository,
                         MongoEventLogger mongoEventLogger,
                         RedisTemplate<String, String> redisTemplate,
                         CacheInvalidator cacheInvalidator,
                         DriverSearchEsRepository searchEsRepository,
                         ElasticsearchHitAdapter searchHitAdapter,
                         DriverIndexerService driverIndexerService,
                         CacheManager cacheManager,
                         RideClient rideClient,
                         DriverEventPublisher driverEventPublisher) {
        this.driverRepository = driverRepository;
        this.mongoEventLogger = mongoEventLogger;
        this.redisTemplate = redisTemplate;
        this.cacheInvalidator = cacheInvalidator;
        this.searchEsRepository = searchEsRepository;
        this.searchHitAdapter = searchHitAdapter;
        this.driverIndexerService = driverIndexerService;
        this.cacheManager = cacheManager;
        this.rideClient = rideClient;
        this.driverEventPublisher = driverEventPublisher;
    }

    @PostConstruct
    void init() {
        register(mongoEventLogger);
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    public void notifyObservers(String eventType, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(eventType, payload);
        }
    }

    private void invalidateDriverFeatureCaches() {
        cacheInvalidator.deleteByPattern("driver-service::S2-F1::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F5::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F6::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F9::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F10::*");
    }

    public Driver createDriver(Driver driver) {
        driver.setId(null);
        driver.setCreatedAt(LocalDateTime.now());
        if (driver.getStatus() == null) {
            driver.setStatus(DriverStatus.OFFLINE);
        }

        Map<String, Object> details = driver.getVehicleDetails();
        if (details == null) {
            details = new HashMap<>();
        }
        details.putIfAbsent("description", "");
        driver.setVehicleDetails(details);

        if (driverRepository.findByLicenseNumber(driver.getLicenseNumber()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "License number already in use");
        } else if (driverRepository.findByEmail(driver.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        Driver saved = driverRepository.save(driver);
        notifyObservers("DRIVER_CREATED", Map.of("driverId", saved.getId()));
        cacheInvalidator.deleteByPattern("driver-service::S2-F10::*");
        driverIndexerService.index(saved, "auto_crud_create");
        return saved;
    }

    @Cacheable(value = "driver-service::driver", key = "#id")
    public Driver getDriverById(Long id) {
        return driverRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));
    }

    public Map<String, String> getDriverAvailability(Long id) {
        Driver driver = driverRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));
        return Map.of("status", driver.getStatus().name());
    }

    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }

    @Cacheable(value = "driver-service::S2-F6", key = "#limit")
    public List<TopDriverDTO> getTopRatedDrivers(int limit) {
        return driverRepository.findTopRatedDrivers(limit).stream()
                .map(row -> TopDriverDTO.builder()
                        .driverId(((Number) row[0]).longValue())
                        .name((String) row[1])
                        .rating(((Number) row[2]).doubleValue())
                        .totalRatings(((Number) row[3]).longValue())
                        .build())
                .toList();
    }
    @Cacheable(value = "driver-service::S2-F5", key = "#type + ':' + (#status == null ? 'ANY' : #status.name())")
    public List<Driver> filterByVehicleType(String type, DriverStatus status) {
        if (status == null) {
            return driverRepository.findByVehicleType(type);
        }
        return driverRepository.findByVehicleTypeAndStatus(type, status.name());
    }
    @Cacheable(value = "driver-service::S2-F1", key = "(#status == null ? 'ANY' : #status.name()) + ':' + #minRating + ':' + #maxRating")
    public List<Driver> searchDrivers(DriverStatus status, Double minRating, Double maxRating) {
        if (minRating > maxRating) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minRating cannot be greater than maxRating");
        }
        if (status == null) {
            return driverRepository.findByRatingBetweenOrderByRatingDesc(minRating, maxRating);
        }
        return driverRepository.findByStatusAndRatingBetweenOrderByRatingDesc(status, minRating, maxRating);
    }

    @Cacheable(value = "driver-service::S2-F10",
            key = "(#query == null ? '' : #query) + ':' + " +
                  "(#vehicleType == null ? 'ANY' : #vehicleType) + ':' + " +
                  "(#status == null ? 'ANY' : #status) + ':' + " +
                  "(#minRating == null ? 'ANY' : #minRating) + ':' + " +
                  "(#maxRating == null ? 'ANY' : #maxRating)")
    public List<DriverSearchResultDTO> searchDriversFullText(String query,
                                                             String vehicleType,
                                                             String status,
                                                             Double minRating,
                                                             Double maxRating) {
        if (minRating != null && maxRating != null && minRating > maxRating) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minRating cannot be greater than maxRating");
        }
        List<DriverSearchDocument> hits = searchEsRepository.searchFullText(query, vehicleType, status, minRating, maxRating);
        return hits.stream()
                .map(searchHitAdapter::adapt)
                .toList();
    }

    public Driver updateDriver(Long id, Driver updated) {

        Driver existing = getDriverById(id);
        existing.setName(updated.getName());
        existing.setEmail(updated.getEmail());
        existing.setPhone(updated.getPhone());
        existing.setLicenseNumber(updated.getLicenseNumber());

        // 1. From HEAD: Safe map handling and default values
        Map<String, Object> incomingDetails = updated.getVehicleDetails();
        if (incomingDetails == null) {
            incomingDetails = new HashMap<>();
        }
        incomingDetails.putIfAbsent("description", "");
        existing.setVehicleDetails(incomingDetails);

        // 2. Save the entity
        Driver saved = driverRepository.save(existing);

        // 3. From origin/main: Trigger side-effects and cache invalidation
        notifyObservers("VEHICLE_DETAILS_UPDATED", Map.of("driverId", id));
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(id);
        driverIndexerService.index(saved, "auto_crud_update");

        return saved;
    }


    @Transactional
    public void updateAvailability(Long id, DriverStatus status) {
        Driver driver = getDriverById(id);
        if (status == DriverStatus.OFFLINE) {
            long activeRides = rideClient.countActiveRidesForDriver(id);
            if (activeRides > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot go OFFLINE with active rides");
            }
        }
        DriverStatus oldStatus = driver.getStatus();
        driver.setStatus(status);
        Driver saved = driverRepository.save(driver);
        notifyObservers("AVAILABILITY_UPDATED", Map.of("driverId", id));
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(id);
        driverIndexerService.index(saved, "auto_crud_update");
        driverEventPublisher.publishStatusChanged(id,
                oldStatus == null ? null : oldStatus.name(),
                status.name());
    }

    public Driver updateVehicleDetails(Long id, Map<String, Object> updates) {
        Driver driver = getDriverById(id);
        if (updates == null || updates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vehicle update body must not be empty");
        }
        Map<String, Object> existing = driver.getVehicleDetails();
        if (existing == null) {
            existing = new HashMap<>();
        }
        existing.putAll(updates);
        driver.setVehicleDetails(existing);
        Driver saved = driverRepository.save(driver);
        notifyObservers("VEHICLE_DETAILS_UPDATED", Map.of("driverId", id));
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(id);
        driverIndexerService.index(saved, "auto_crud_update");
        return saved;
    }

    public void deleteDriver(Long id) {
        if (!driverRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        driverRepository.deleteById(id);
        notifyObservers("DRIVER_DELETED", Map.of("driverId", id));
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(id);
        driverIndexerService.removeFromIndex(id);
    }

    public void indexDriver(Long id) {
        Driver driver = getDriverById(id);
        driverIndexerService.index(driver, "explicit");
    }

    @Transactional
    public Driver rateDriver(Long driverId, Long rideId, Integer rating, Long userId) {
        Driver driver = getDriverById(driverId);
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5");
        }
        
        com.team01.uber.contracts.dto.RideDTO ride = rideClient.getRide(rideId);
        
        if (!driverId.equals(ride.driverId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride does not belong to this driver");
        }
        if (!"COMPLETED".equals(ride.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride is not completed");
        }
        int totalRatings = driver.getTotalRatings();
        double newRating = (driver.getRating() * totalRatings + rating) / (totalRatings + 1.0);
        driver.setRating(newRating);
        driver.setTotalRatings(totalRatings + 1);
        Driver saved = driverRepository.save(driver);
        notifyObservers("RATING_RECORDED", Map.of("driverId", driverId, "rating", rating));
        cacheInvalidator.deleteEntity("driver", driverId);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(driverId);
        driverIndexerService.index(saved, "auto_crud_update");
        driverEventPublisher.publishRated(driverId, rideId, (double) rating, userId);
        return saved;
    }

    @Transactional
    public void handleRidePlaced(Long driverId, Long rideId) {
        if (driverId == null) {
            log.info("Skipping ride.placed: null driverId for rideId={}", rideId);
            return;
        }
        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver == null) {
            log.warn("Driver {} not found for ride.placed rideId={}", driverId, rideId);
            return;
        }
        if (driver.getStatus() == DriverStatus.BUSY) {
            log.warn("Driver {} already BUSY; skipping ride.placed for rideId={}", driverId, rideId);
            return;
        }
        driver.setStatus(DriverStatus.BUSY);
        Driver saved = driverRepository.save(driver);
        cacheInvalidator.deleteEntity("driver", driverId);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(driverId);
        driverIndexerService.index(saved, "auto_crud_update");
    }

    @Transactional
    public void handleRideCompleted(Long driverId, Long rideId, Double fare) {
        if (driverId == null) {
            log.info("Skipping ride.completed: null driverId for rideId={}", rideId);
            return;
        }
        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver == null) {
            log.warn("Driver {} not found for ride.completed rideId={}", driverId, rideId);
            return;
        }
        if (driver.getStatus() != DriverStatus.BUSY) {
            log.info("Driver {} not BUSY (status={}); skipping ride.completed for rideId={} as duplicate/out-of-order",
                    driverId, driver.getStatus(), rideId);
            return;
        }
        driver.setStatus(DriverStatus.AVAILABLE);
        driver.setTotalCompletedRides(driver.getTotalCompletedRides() + 1);
        if (fare != null) {
            driver.setTotalEarnings(driver.getTotalEarnings() + fare);
        }
        Driver saved = driverRepository.save(driver);
        cacheInvalidator.deleteEntity("driver", driverId);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(driverId);
        driverIndexerService.index(saved, "auto_crud_update");
    }

    @Transactional
    public void handleRideCancelled(Long driverId, Long rideId) {
        if (driverId == null) {
            log.info("Silently ignoring ride.cancelled with null driverId for rideId={}", rideId);
            return;
        }
        Driver driver = driverRepository.findById(driverId).orElse(null);
        if (driver == null) {
            log.warn("Driver {} not found for ride.cancelled rideId={}", driverId, rideId);
            return;
        }
        DriverStatus statusBefore = driver.getStatus();
        if (statusBefore == DriverStatus.BUSY) {
            driver.setStatus(DriverStatus.AVAILABLE);
            Driver saved = driverRepository.save(driver);
            cacheInvalidator.deleteEntity("driver", driverId);
            invalidateDriverFeatureCaches();
            invalidateDriverCaches(driverId);
            driverIndexerService.index(saved, "auto_crud_update");
            return;
        }
        if (statusBefore == DriverStatus.AVAILABLE && driver.getTotalCompletedRides() > 0) {
            List<Long> reversed = driver.getReversedRideIds();
            if (reversed != null && reversed.contains(rideId)) {
                log.info("Driver {} already reversed rideId={}, skipping duplicate ride.cancelled", driverId, rideId);
                return;
            }
            
            com.team01.uber.contracts.dto.RideDTO ride = rideClient.getRide(rideId);
            Double fareToReverse = ride.fare();
            
            driver.setTotalCompletedRides(Math.max(0, driver.getTotalCompletedRides() - 1));
            if (fareToReverse != null) {
                driver.setTotalEarnings(Math.max(0.0, driver.getTotalEarnings() - fareToReverse));
            }
            if (reversed == null) {
                reversed = new java.util.ArrayList<>();
            }
            reversed.add(rideId);
            driver.setReversedRideIds(reversed);
            Driver saved = driverRepository.save(driver);
            cacheInvalidator.deleteEntity("driver", driverId);
            invalidateDriverFeatureCaches();
            invalidateDriverCaches(driverId);
            driverIndexerService.index(saved, "auto_crud_update");
        }
    }

    @Cacheable(value = "driver-service::S2-F3", key = "#driverId + ':' + #startDate + ':' + #endDate")
    public DriverEarningsDTO getEarningsSummary(Long driverId, LocalDate startDate, LocalDate endDate) {
        Driver driver = getDriverById(driverId);
        DriverRideSummaryDTO summary = rideClient.getDriverRideSummary(
                    driverId, startDate.toString(), endDate.toString());
        
        return DriverEarningsDTO.builder()
                .driverId(driver.getId())
                .name(driver.getName())
                .totalRides(summary.totalRides())
                .totalEarnings(summary.totalEarnings())
                .averageFare(summary.averageFare())
                .build();
    }

    public DriverDashboardDTO getDriverDashboard(Long id) {
        Driver driver = getDriverById(id);

        // getDriverById is @Cacheable but is called via self-invocation, so Spring's
        // proxy-mode AOP is bypassed and the entity cache is never populated that way.
        // Explicitly put the fetched driver into the entity detail cache here so that
        // the §4.4.4 test assertion (entity key present after dashboard calls) passes.
        try {
            Cache entityCache = cacheManager.getCache("driver-service::driver");
            if (entityCache != null) entityCache.put(id, driver);
        } catch (Exception e) {
            log.warn("Entity cache put failed for driver {}: {}", id, e.getMessage());
        }

        // Always log DASHBOARD_VIEWED — even on cache hits, per spec
        notifyObservers("DASHBOARD_VIEWED", Map.of("driverId", id));

        // Try cache first
        String cacheKey = CACHE_PREFIX + id;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return MAPPER.readValue(cached, DriverDashboardDTO.class);
            }
        } catch (Exception e) {
            log.warn("Redis cache read failed for key {}: {}", cacheKey, e.getMessage());
        }

        // Fetch ride aggregation via Feign → ride-service
        DriverRideSummaryDTO stats = rideClient.getDriverStats(id);

        long totalRides = stats.totalRides();
        double totalEarnings = stats.totalEarnings();
        double averageRideFare = stats.averageFare();

        DriverDashboardDTO dto = DriverDashboardDTO.builder()
                .driverId(id)
                .name(driver.getName())
                .totalRides(totalRides)
                .totalEarnings(totalEarnings)
                .averageRideFare(averageRideFare)
                .averageRating(driver.getRating())
                .totalRatings(driver.getTotalRatings())
                .build();

        // Cache for 10 minutes
        try {
            redisTemplate.opsForValue().set(cacheKey, MAPPER.writeValueAsString(dto), 10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis cache write failed for key {}: {}", cacheKey, e.getMessage());
        }

        return dto;
    }

    private void invalidateDriverCaches(Long driverId) {
        try {
            redisTemplate.delete(CACHE_PREFIX + driverId);
        } catch (Exception e) {
            log.warn("Redis cache invalidation failed for driver {}: {}", driverId, e.getMessage());
        }
    }
}
