package com.team01.uber.driver.service;

import com.team01.uber.driver.dto.DriverEarningsDTO;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.repository.DriverRepository;
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

    public DriverService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    public Driver createDriver(Driver driver) {
        driver.setId(null); // Ensure ID is null for new document
        driver.setCreatedAt(LocalDateTime.now());
        if (driver.getStatus() == null) {
            driver.setStatus(DriverStatus.OFFLINE);
        }
        return driverRepository.save(driver);
    }

    public Driver getDriverById(Long id) {
        return driverRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found"));
    }

    public List<Driver> getAllDrivers() {
        return driverRepository.findAll();
    }

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
        return driverRepository.save(existing);
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
        return driverRepository.save(driver);
    }

    public void deleteDriver(Long id) {
        if (!driverRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        driverRepository.deleteById(id);
    }

    public DriverEarningsDTO getEarningsSummary(Long driverId, LocalDate startDate, LocalDate endDate) {
        Driver driver = getDriverById(driverId);

        Object[] row = driverRepository.getEarningsSummary(driverId, startDate, endDate);
        // native query returns a single-row result; each element is a column value
        // Spring Data may wrap as Object[] where element 0 is itself an Object[] row
        if (row.length > 0 && row[0] instanceof Object[]) {
            row = (Object[]) row[0];
        }

        Long totalRides = ((Number) row[0]).longValue();
        Double totalEarnings = ((Number) row[1]).doubleValue();
        Double averageFare = ((Number) row[2]).doubleValue();

        return new DriverEarningsDTO(driver.getId(), driver.getName(), totalRides, totalEarnings, averageFare);
    }
}
