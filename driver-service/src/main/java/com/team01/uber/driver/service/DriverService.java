package com.team01.uber.driver.service;

import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.repository.DriverRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DriverService {

    // Local record to deserialise only the fields we need from ride-service
    record RideResponse(Long id, Long driverId, String status) {}

    private final DriverRepository driverRepository;
    private final RestClient rideServiceClient;

    public DriverService(DriverRepository driverRepository, RestClient rideServiceClient) {
        this.driverRepository = driverRepository;
        this.rideServiceClient = rideServiceClient;
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

    @Transactional
    public Driver rateDriver(Long driverId, Long rideId, Integer rating) {
        // 1. Find driver — 404 if not found
        Driver driver = getDriverById(driverId);

        // 2. Validate rating range — 400 if out of bounds
        if (rating < 1 || rating > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rating must be between 1 and 5");
        }

        // 3. Fetch ride from ride-service — 404 if not found
        RideResponse ride = rideServiceClient.get()
                .uri("/api/rides/{id}", rideId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
                })
                .body(RideResponse.class);

        if (ride == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found");
        }

        // 4. Verify ride belongs to this driver — 400 if not
        if (!driverId.equals(ride.driverId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride does not belong to this driver");
        }

        // 5. Verify ride is COMPLETED — 400 if not
        if (!"COMPLETED".equals(ride.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ride is not completed");
        }

        // 6. Recalculate running average and update driver
        int totalRatings = driver.getTotalRatings();
        double newRating = (driver.getRating() * totalRatings + rating) / (totalRatings + 1.0);

        driver.setRating(newRating);
        driver.setTotalRatings(totalRatings + 1);

        return driverRepository.save(driver);
    }
}
