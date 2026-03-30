package com.team01.uber.driver.service;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.repository.DriverRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

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
        existing.setStatus(updated.getStatus());
        existing.setVehicleDetails(updated.getVehicleDetails());
        return driverRepository.save(existing);
    }

    public void deleteDriver(Long id) {
        if (!driverRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        driverRepository.deleteById(id);
    }
}
