package com.team01.uber.location.service;

import com.team01.uber.location.client.DriverLookupService;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.repository.LocationRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final DriverLookupService driverLookupService;

    public LocationService(LocationRepository locationRepository, DriverLookupService driverLookupService) {
        this.locationRepository = locationRepository;
        this.driverLookupService = driverLookupService;
    }

    public Location create(Location location) {
        return locationRepository.save(location);
    }

    public Location getById(Long id) {
        return locationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Error 404"));
    }

    public List<Location> getAll() {
        return locationRepository.findAll();
    }

    public Location update(Long id, Location location) {
        Location existing = getById(id);
        if(existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error 404");
        }
        existing.setDriverId(location.getDriverId());
        existing.setLatitude(location.getLatitude());
        existing.setLongitude(location.getLongitude());
        existing.setTimestamp(location.getTimestamp());
        existing.setMetadata(location.getMetadata());
        return locationRepository.save(existing);
    }

    public void delete(Long id) {
        if (!locationRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Error 404");
        }
        locationRepository.deleteById(id);
    }

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

    public Location getLatestByDriverId(Long driverId) {
        if (!driverLookupService.existsById(driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        return locationRepository.findTopByDriverIdOrderByTimestampDescIdDesc(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No locations found for driver"));
    }
}
