package com.team01.uber.driver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.driver.adapter.ElasticsearchHitAdapter;
import com.team01.uber.driver.cache.CacheInvalidator;
import com.team01.uber.driver.dto.DriverDashboardDTO;
import com.team01.uber.driver.dto.DriverEarningsDTO;
import com.team01.uber.driver.dto.DriverSearchResultDTO;
import com.team01.uber.driver.dto.TopDriverDTO;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.observer.EntityObserver;
import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.repository.DriverRepository;
import com.team01.uber.driver.repository.DriverSearchEsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
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
    private final List<EntityObserver> observers = new ArrayList<>();

    public DriverService(DriverRepository driverRepository,
                         MongoEventLogger mongoEventLogger,
                         RedisTemplate<String, String> redisTemplate,
                         CacheInvalidator cacheInvalidator,
                         DriverSearchEsRepository searchEsRepository,
                         ElasticsearchHitAdapter searchHitAdapter) {
        this.driverRepository = driverRepository;
        this.mongoEventLogger = mongoEventLogger;
        this.redisTemplate = redisTemplate;
        this.cacheInvalidator = cacheInvalidator;
        this.searchEsRepository = searchEsRepository;
        this.searchHitAdapter = searchHitAdapter;
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
        return saved;
    }

    @Cacheable(value = "driver-service::driver", key = "#id")
    public Driver getDriverById(Long id) {
        return driverRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));
    }

    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }

    @Cacheable(value = "driver-service::S2-F6", key = "#limit")
    public List<TopDriverDTO> getTopRatedDrivers(int limit) {
        return driverRepository.findTopRatedDrivers(limit).stream()
                .map(row -> new TopDriverDTO(
                        ((Number) row[0]).longValue(),
                        (String) row[1],
                        ((Number) row[2]).doubleValue(),
                        ((Number) row[3]).longValue()
                ))
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
        @SuppressWarnings("rawtypes")
        SearchHits<Map> hits = searchEsRepository.searchFullText(query, vehicleType, status, minRating, maxRating);
        return hits.getSearchHits().stream()
                .map(this::adaptHit)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private DriverSearchResultDTO adaptHit(SearchHit<?> hit) {
        return searchHitAdapter.adapt((SearchHit<Map<String, Object>>) hit);
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

        return saved;
    }


    @Transactional
    public void updateAvailability(Long id, DriverStatus status) {
        Driver driver = getDriverById(id);
        if (status == DriverStatus.OFFLINE) {
            long activeRides = driverRepository.countActiveRidesByDriverId(id);
            if (activeRides > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot go OFFLINE with active rides");
            }
        }
        driver.setStatus(status);
        driverRepository.save(driver);
        notifyObservers("AVAILABILITY_UPDATED", Map.of("driverId", id));
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        invalidateDriverCaches(id);
    }

    public Driver updateVehicleDetails(Long id, Map<String, Object> updates) {
        Driver driver = getDriverById(id);
        if (updates == null || updates.isEmpty()) {
            return driver;
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
    }

    @Transactional
    public Driver rateDriver(Long driverId, Long rideId, Integer rating) {
        Driver driver = getDriverById(driverId);
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5");
        }
        if (!driverRepository.rideExists(rideId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }
        if (!driverRepository.rideBelongsToDriver(rideId, driverId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride does not belong to this driver");
        }
        String rideStatus = driverRepository.getRideStatus(rideId);
        if (!"COMPLETED".equals(rideStatus)) {
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
        return saved;
    }

    @Cacheable(value = "driver-service::S2-F3", key = "#driverId + ':' + #startDate + ':' + #endDate")
    public DriverEarningsDTO getEarningsSummary(Long driverId, LocalDate startDate, LocalDate endDate) {
        Driver driver = getDriverById(driverId);
        Object[] row = driverRepository.getEarningsSummary(driverId, startDate, endDate);
        if (row.length > 0 && row[0] instanceof Object[]) {
            row = (Object[]) row[0];
        }
        Long totalRides = ((Number) row[0]).longValue();
        Double totalEarnings = ((Number) row[1]).doubleValue();
        Double averageFare = ((Number) row[2]).doubleValue();
        return new DriverEarningsDTO(driver.getId(), driver.getName(), totalRides, totalEarnings, averageFare);
    }

    public DriverDashboardDTO getDriverDashboard(Long id) {
        Driver driver = getDriverById(id);

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

        // Query PostgreSQL
        Object[] row = driverRepository.getDashboardStats(id);
        if (row.length > 0 && row[0] instanceof Object[]) {
            row = (Object[]) row[0];
        }

        long totalRides = ((Number) row[0]).longValue();
        double totalEarnings = ((Number) row[1]).doubleValue();
        double averageRideFare = ((Number) row[2]).doubleValue();

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
