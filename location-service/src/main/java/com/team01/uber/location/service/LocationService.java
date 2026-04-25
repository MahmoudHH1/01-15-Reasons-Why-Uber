package com.team01.uber.location.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.team01.uber.location.adapter.CassandraRowAdapter;
import com.team01.uber.location.dto.BatchLocationRequest;
import com.team01.uber.location.dto.BatchLocationResponse;
import com.team01.uber.location.dto.DriverLocationCreateRequest;
import com.team01.uber.location.dto.DriverMovementSummaryDTO;
import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.dto.NearbyDriverDTO;
import com.team01.uber.location.dto.StationaryDriverDTO;
import com.team01.uber.location.dto.TrackingRequest;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.model.LocationTrackingEvent;
import com.team01.uber.location.model.LocationTrackingEventKey;
import com.team01.uber.location.observer.EntityObserver;
import com.team01.uber.location.repository.LocationRepository;
import com.team01.uber.location.repository.LocationTrackingRepository;

import jakarta.transaction.Transactional;

@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final LocationTrackingRepository trackingRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final List<EntityObserver> observers = new CopyOnWriteArrayList<>();

    public LocationService(LocationRepository locationRepository,
                           LocationTrackingRepository trackingRepository,
                           RedisTemplate<String, Object> redisTemplate) {
        this.locationRepository = locationRepository;
        this.trackingRepository = trackingRepository;
        this.redisTemplate = redisTemplate;
    }

    public void register(EntityObserver observer) {
        observers.add(observer);
    }

    public void unregister(EntityObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(String action, Object payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(action, payload);
        }
    }

    public Location create(Location location) {
        return locationRepository.save(location);
    }

    public Location createForDriver(Long driverId, DriverLocationCreateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must not be null");
        }

        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        Double latitude = request.getLatitude();
        Double longitude = request.getLongitude();
        if (latitude == null || longitude == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude and longitude are required");
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude or longitude out of valid range");
        }

        Location location = new Location();
        location.setId(null);
        location.setDriverId(driverId);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setMetadata(request.getMetadata());
        location.setTimestamp(LocalDateTime.now());

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
        if (location.getDriverId() != null) existing.setDriverId(location.getDriverId());
        if (location.getLatitude() != null) existing.setLatitude(location.getLatitude());
        if (location.getLongitude() != null) existing.setLongitude(location.getLongitude());
        if (location.getTimestamp() != null) existing.setTimestamp(location.getTimestamp());
        if (location.getMetadata() != null) existing.setMetadata(location.getMetadata());
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
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must not be blank");
        }
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
        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        return locationRepository.findTopByDriverIdOrderByTimestampDescIdDesc(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No locations found for driver"));
    }

    public List<Location> getLocationsInDateRange(String startDate, String endDate, Long driverId) {
        LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
        LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
        if (driverId != null) {
            return locationRepository.findInDateRangeByDriver(start, end, driverId);
        }
        return locationRepository.findInDateRange(start, end);
    }

    public DriverMovementSummaryDTO getDriverMovementSummary(Long driverId, String startDate, String endDate) {
        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        LocalDateTime start = startDate.contains("T") ? LocalDateTime.parse(startDate) : LocalDate.parse(startDate).atStartOfDay();
        LocalDateTime end = endDate.contains("T") ? LocalDateTime.parse(endDate) : LocalDate.parse(endDate).atTime(LocalTime.MAX);

        List<Object[]> results = locationRepository.getMovementSummary(driverId, start, end);
        Object[] row = results.get(0);

        long totalPoints = ((Number) row[0]).longValue();
        Double avgSpeed = (totalPoints > 0 && row[1] != null) ? ((Number) row[1]).doubleValue() : 0.0;
        Double maxSpeed = row[2] != null ? ((Number) row[2]).doubleValue() : null;
        LocalDateTime firstTs = row[3] != null ? (LocalDateTime) row[3] : null;
        LocalDateTime lastTs  = row[4] != null ? (LocalDateTime) row[4] : null;

        return new DriverMovementSummaryDTO(driverId, totalPoints, avgSpeed, maxSpeed, firstTs, lastTs);
    }

    public List<StationaryDriverDTO> findStationaryDrivers(Double maxSpeed, int sinceMinutes) {
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(sinceMinutes);
        List<Object[]> results = locationRepository.findStationaryDrivers(maxSpeed, since);
        return results.stream().map(row -> new StationaryDriverDTO(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (Double) row[2],
                (Double) row[3],
                row[4] != null ? ((Number) row[4]).doubleValue() : null,
                (LocalDateTime) row[5]
        )).toList();
    }

    public List<NearbyDriverDTO> findNearbyDrivers(Double lat, Double lon, Double radiusKm) {
        List<Object[]> results = locationRepository.findNearbyAvailableDrivers(lat, lon, radiusKm);
        return results.stream().map(row -> new NearbyDriverDTO(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (Double) row[2],
                (Double) row[3],
                (Double) row[4]
        )).toList();
    }

    public LocationTrackingDTO recordGpsEvent(Long driverId, TrackingRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body must not be null");
        }
        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }
        if (request.getLatitude() == null || request.getLongitude() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude and longitude are required");
        }
        if (request.getLatitude() < -90 || request.getLatitude() > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude must be between -90 and 90");
        }
        if (request.getLongitude() < -180 || request.getLongitude() > 180) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Longitude must be between -180 and 180");
        }

        Instant now = Instant.now();
        LocationTrackingEventKey key = new LocationTrackingEventKey(driverId, now);

        LocationTrackingEvent event = new LocationTrackingEvent();
        event.setKey(key);
        event.setLatitude(request.getLatitude());
        event.setLongitude(request.getLongitude());
        event.setSpeed(request.getSpeed());
        event.setHeading(request.getHeading());
        event.setAccuracy(request.getAccuracy());
        event.setRideId(request.getRideId());
        event.setNotes(request.getNotes());

        trackingRepository.save(event);

        // Invalidate Redis caches for latest location and nearby drivers
        redisTemplate.delete("location-service::S4-F12::" + driverId);
        redisTemplate.keys("location-service::S4-F10::*").forEach(redisTemplate::delete);

        // Notify observers (MongoDB event logging)
        Map<String, Object> payload = new HashMap<>();
        payload.put("driverId", driverId);
        payload.put("latitude", request.getLatitude());
        payload.put("longitude", request.getLongitude());
        notifyObservers("TRACKING_RECORDED", payload);

        return CassandraRowAdapter.adapt(event);
    }
}