package com.team01.uber.location.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.team01.uber.location.client.DriverLookupService;
import com.team01.uber.location.dto.BatchLocationRequest;
import com.team01.uber.location.dto.BatchLocationResponse;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.repository.LocationRepository;

import jakarta.transaction.Transactional;

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
    public BatchLocationResponse batchUpdate(BatchLocationRequest request) {
        Long driverId = request.getDriverId();
        if (driverId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "driverId is required");
        }

        List<Location> items = request.getLocations();
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "locations must not be null or empty");
        }

        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

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

    public List<Location> filterByMetadata(String key, String operator, String value) {
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

    public Location getLatestByDriverId(Long driverId) {
        if (!driverLookupService.existsById(driverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        return locationRepository.findTopByDriverIdOrderByTimestampDescIdDesc(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No locations found for driver"));
    }
}
