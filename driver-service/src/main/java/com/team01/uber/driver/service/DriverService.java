package com.team01.uber.driver.service;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.repository.DriverRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
        driver.setRating(0.0);
        driver.setTotalRatings(0);
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

    public void deleteDriver(Long id) {
        if (!driverRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        driverRepository.deleteById(id);
    }
}
