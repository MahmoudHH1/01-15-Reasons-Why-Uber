package com.team01.uber.location.service;

import com.team01.uber.location.entity.Location;
import com.team01.uber.location.repository.LocationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class LocationService {

    private final LocationRepository locationRepository;

    public LocationService(LocationRepository locationRepository) {
        this.locationRepository = locationRepository;
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
        locationRepository.deleteById(id);
    }
}
