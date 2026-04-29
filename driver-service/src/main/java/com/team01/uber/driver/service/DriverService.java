package com.team01.uber.driver.service;

import com.team01.uber.driver.cache.CacheInvalidator;
import com.team01.uber.driver.dto.TopDriverDTO;
import com.team01.uber.driver.dto.DriverEarningsDTO;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.repository.DriverRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DriverService {

    private final DriverRepository driverRepository;
    private final CacheInvalidator cacheInvalidator;

    public DriverService(DriverRepository driverRepository, CacheInvalidator cacheInvalidator) {
        this.driverRepository = driverRepository;
        this.cacheInvalidator = cacheInvalidator;
    }

    private void invalidateDriverFeatureCaches() {
        cacheInvalidator.deleteByPattern("driver-service::S2-F1::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F5::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F6::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F9::*");
        cacheInvalidator.deleteByPattern("driver-service::S2-F10::*");
    }

    public Driver createDriver(Driver driver) {
        driver.setId(null); // Ensure ID is null for new document
        driver.setCreatedAt(LocalDateTime.now());
        if (driver.getStatus() == null) {
            driver.setStatus(DriverStatus.OFFLINE);
        }

        if (driverRepository.findByLicenseNumber(driver.getLicenseNumber()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "License number already in use");
        }
        else if (driverRepository.findByEmail(driver.getEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use");
        }

        Driver saved = driverRepository.save(driver);
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

    public Driver updateDriver(Long id, Driver updated) {
        Driver existing = getDriverById(id);
        existing.setName(updated.getName());
        existing.setEmail(updated.getEmail());
        existing.setPhone(updated.getPhone());
        existing.setLicenseNumber(updated.getLicenseNumber());
        existing.setVehicleDetails(updated.getVehicleDetails());
        Driver saved = driverRepository.save(existing);
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        return saved;
    }

    @Transactional
    public void updateAvailability(Long id, DriverStatus status) {
        Driver driver = getDriverById(id);

        if (status == DriverStatus.OFFLINE) {
            long activeRides = driverRepository.countActiveRidesByDriverId(id);
            if (activeRides > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot go OFFLINE with active rides");
            }
        }

        driver.setStatus(status);
        driverRepository.save(driver);
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
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
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
        return saved;
    }

    public void deleteDriver(Long id) {
        if (!driverRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        driverRepository.deleteById(id);
        cacheInvalidator.deleteEntity("driver", id);
        invalidateDriverFeatureCaches();
    }

    @Transactional
    public Driver rateDriver(Long driverId, Long rideId, Integer rating) {
        // 1. Find driver — 404 if not found
        Driver driver = getDriverById(driverId);

        // 2. Validate rating range — 400 if out of bounds
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5");
        }

        // 3. Verify ride exists — 404 if not found
        if (!driverRepository.rideExists(rideId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }

        // 4. Verify ride belongs to this driver — 400 if not
        if (!driverRepository.rideBelongsToDriver(rideId, driverId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride does not belong to this driver");
        }

        // 5. Verify ride is COMPLETED — 400 if not
        String rideStatus = driverRepository.getRideStatus(rideId);
        if (!"COMPLETED".equals(rideStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride is not completed");
        }

        // 6. Recalculate running average and update driver
        int totalRatings = driver.getTotalRatings();
        double newRating = (driver.getRating() * totalRatings + rating) / (totalRatings + 1.0);

        driver.setRating(newRating);
        driver.setTotalRatings(totalRatings + 1);

        Driver saved = driverRepository.save(driver);
        cacheInvalidator.deleteEntity("driver", driverId);
        invalidateDriverFeatureCaches();
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
}
