package com.team01.uber.location.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.team01.uber.location.adapter.LocationAdapter;
import com.team01.uber.location.dto.BatchLocationRequest;
import com.team01.uber.location.dto.BatchLocationResponse;
import com.team01.uber.location.dto.DriverLocationCreateRequest;
import com.team01.uber.location.dto.DriverMovementSummaryDTO;
import com.team01.uber.location.dto.LocationTrackingDTO;
import com.team01.uber.location.dto.LocationAnalyticsDTO;
import com.team01.uber.location.dto.NearbyDriverDTO;
import com.team01.uber.location.dto.StationaryDriverDTO;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.model.LocationTrackingEvent;
import com.team01.uber.location.repository.LocationRepository;
import com.team01.uber.location.repository.LocationTrackingEventRepository;

import jakarta.transaction.Transactional;

@Service
public class LocationService {

    private final LocationRepository locationRepository;
    private final LocationTrackingEventRepository trackingRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final LocationAdapter locationAdapter = new LocationAdapter();

    @SuppressWarnings("unchecked")
    public LocationService(LocationRepository locationRepository,
                           LocationTrackingEventRepository trackingRepository,
                           RedisTemplate redisTemplate) {
        this.locationRepository = locationRepository;
        this.trackingRepository = trackingRepository;
        this.redisTemplate = redisTemplate;
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

    @Cacheable(value = "location-service::location", key = "#id")
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

    @Cacheable(value = "location-service::S4-F5", key = "#key + ':' + #operator + ':' + #value")
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

    @Cacheable(value = "location-service::S4-F1", key = "#driverId")
    public Location getLatestByDriverId(Long driverId) {
        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        return locationRepository.findTopByDriverIdOrderByTimestampDescIdDesc(driverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No locations found for driver"));
    }

    @Cacheable(value = "location-service::S4-F6", key = "#startDate + ':' + #endDate + ':' + #driverId")
    public List<Location> getLocationsInDateRange(String startDate, String endDate, Long driverId) {
        LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
        LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
        if (driverId != null) {
            return locationRepository.findInDateRangeByDriver(start, end, driverId);
        }
        return locationRepository.findInDateRange(start, end);
    }

    @Cacheable(value = "location-service::S4-F8", key = "#driverId + ':' + #startDate + ':' + #endDate")
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

        return DriverMovementSummaryDTO.builder()
                .driverId(driverId)
                .totalLocationPoints(totalPoints)
                .averageSpeed(avgSpeed)
                .maxSpeed(maxSpeed)
                .firstTimestamp(firstTs)
                .lastTimestamp(lastTs)
                .build();
    }

    @Cacheable(value = "location-service::S4-F9", key = "#maxSpeed + ':' + #sinceMinutes")
    public List<StationaryDriverDTO> findStationaryDrivers(Double maxSpeed, int sinceMinutes) {
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(sinceMinutes);
        List<Object[]> results = locationRepository.findStationaryDrivers(maxSpeed, since);
        return results.stream().map(row -> StationaryDriverDTO.builder()
                .driverId(((Number) row[0]).longValue())
                .driverName((String) row[1])
                .latitude((Double) row[2])
                .longitude((Double) row[3])
                .lastSpeed(row[4] != null ? ((Number) row[4]).doubleValue() : null)
                .lastUpdated((LocalDateTime) row[5])
                .build()).toList();
    }

    @Cacheable(value = "location-service::S4-F3", key = "#lat + ':' + #lon + ':' + #radiusKm")
    public List<NearbyDriverDTO> findNearbyDrivers(Double lat, Double lon, Double radiusKm) {
        List<Object[]> results = locationRepository.findNearbyAvailableDrivers(lat, lon, radiusKm);
        return results.stream().map(row -> NearbyDriverDTO.builder()
                .driverId(((Number) row[0]).longValue())
                .driverName((String) row[1])
                .latitude((Double) row[2])
                .longitude((Double) row[3])
                .distanceKm((Double) row[4])
                .build()).toList();
    }

    @Cacheable(value = "location-service::S4-F12",
               key = "#driverId + ':' + #startTime + ':' + #endTime")
    public List<LocationTrackingDTO> getTrackingTimeline(Long driverId, String startTime, String endTime) {
        if (locationRepository.countDriverById(driverId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Driver not found");
        }

        List<LocationTrackingEvent> events;
        if (startTime != null && endTime != null) {
            Instant start = Instant.parse(startTime);
            Instant end = Instant.parse(endTime);
            events = trackingRepository.findByDriverIdAndTimestampBetween(driverId, start, end);
        } else {
            events = trackingRepository.findByDriverId(driverId);
        }

        return events.stream()
                .map(e -> new LocationTrackingDTO(
                        e.getTimestamp(), e.getLatitude(), e.getLongitude(),
                        e.getSpeed(), e.getHeading(), e.getAccuracy(),
                        e.getRideId(), e.getNotes()))
                .toList();
    }

    @Cacheable(value = "location-service::S4-F10", key = "#startDate + ':' + #endDate")
    public LocationAnalyticsDTO getAnalytics(String startDate, String endDate) {
        LocalDateTime start = startDate.contains("T") ? LocalDateTime.parse(startDate) : LocalDate.parse(startDate).atStartOfDay();
        LocalDateTime end = endDate.contains("T") ? LocalDateTime.parse(endDate) : LocalDate.parse(endDate).atTime(LocalTime.MAX);

        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate cannot be after endDate");
        }

        List<Object[]> statsResults = locationRepository.getDashboardStats(start, end);
        List<Object[]> hourlyResults = locationRepository.getEventsByHour(start, end);

        if (statsResults.isEmpty() || statsResults.get(0)[0] == null) {
            // Return empty analytics if no data found
            return LocationAnalyticsDTO.builder()
                    .totalLocationEvents(0L)
                    .activeDrivers(0L)
                    .averageSpeed(0.0)
                    .eventsByHour(new java.util.HashMap<>())
                    .build();
        }

        return locationAdapter.adaptToLocationAnalytics(statsResults.get(0), hourlyResults);
    }
}