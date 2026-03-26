package com.team01.uber.location.controller;

import com.team01.uber.location.client.DriverLookupService;
import com.team01.uber.location.dto.DriverLocationCreateRequest;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LocationControllerLatestLocationTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        FakeDriverLookupService fakeDriverLookupService() {
            return new FakeDriverLookupService();
        }
    }

    static class FakeDriverLookupService implements DriverLookupService {
        private final Set<Long> existingDriverIds = new HashSet<>();

        void registerDriver(Long driverId) {
            existingDriverIds.add(driverId);
        }

        void clear() {
            existingDriverIds.clear();
        }

        @Override
        public boolean existsById(Long driverId) {
            return existingDriverIds.contains(driverId);
        }
    }

    @Autowired
    private LocationController locationController;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private FakeDriverLookupService fakeDriverLookupService;

    @BeforeEach
    void setUp() {
        locationRepository.deleteAll();
        fakeDriverLookupService.clear();
    }

    @Test
    void getLatestByDriverIdReturnsMostRecentLocation() {
        Long driverId = 100L;
        fakeDriverLookupService.registerDriver(driverId);

        createLocation(driverId, LocalDateTime.of(2026, 3, 20, 10, 15));
        createLocation(driverId, LocalDateTime.of(2026, 3, 21, 9, 30));
        createLocation(driverId, LocalDateTime.of(2026, 3, 22, 7, 45));

        ResponseEntity<Location> response = locationController.getLatestByDriverId(driverId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDriverId()).isEqualTo(driverId);
        assertThat(response.getBody().getTimestamp()).isEqualTo(LocalDateTime.of(2026, 3, 22, 7, 45));
    }

    @Test
    void getLatestByDriverIdReturns404WhenDriverHasNoLocations() {
        Long driverId = 200L;
        fakeDriverLookupService.registerDriver(driverId);

        assertThatThrownBy(() -> locationController.getLatestByDriverId(driverId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void getLatestByDriverIdReturns404WhenDriverDoesNotExist() {
        Long driverId = 300L;

        assertThatThrownBy(() -> locationController.getLatestByDriverId(driverId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void createForDriverReturns201AndSavesTimestampAndMetadata() {
        Long driverId = 400L;
        fakeDriverLookupService.registerDriver(driverId);

        LocalDateTime beforeCall = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        DriverLocationCreateRequest request = new DriverLocationCreateRequest();
        request.setLatitude(30.044);
        request.setLongitude(31.235);
        request.setMetadata(Map.of("speed", 45.2));

        ResponseEntity<Location> response = locationController.createForDriver(driverId, request);
        LocalDateTime afterCall = LocalDateTime.now().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        Location saved = response.getBody();
        assertThat(saved.getDriverId()).isEqualTo(driverId);
        assertThat(saved.getLatitude()).isEqualTo(30.044);
        assertThat(saved.getLongitude()).isEqualTo(31.235);
        assertThat(saved.getMetadata()).containsEntry("speed", 45.2);
        assertThat(saved.getTimestamp()).isNotNull();
        assertThat(saved.getTimestamp()).isBetween(beforeCall, afterCall);
    }

    @Test
    void createForDriverReturns404WhenDriverDoesNotExist() {
        Long driverId = 500L;
        DriverLocationCreateRequest request = new DriverLocationCreateRequest();
        request.setLatitude(30.044);
        request.setLongitude(31.235);
        request.setMetadata(Map.of("speed", 45.2));

        assertThatThrownBy(() -> locationController.createForDriver(driverId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void createForDriverReturns400WhenLatitudeOrLongitudeMissing() {
        Long driverId = 600L;
        fakeDriverLookupService.registerDriver(driverId);

        DriverLocationCreateRequest request = new DriverLocationCreateRequest();
        request.setMetadata(Map.of("speed", 45.2));

        assertThatThrownBy(() -> locationController.createForDriver(driverId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    @Test
    void createForDriverReturns400WhenCoordinatesOutOfRange() {
        Long driverId = 700L;
        fakeDriverLookupService.registerDriver(driverId);

        DriverLocationCreateRequest request = new DriverLocationCreateRequest();
        request.setLatitude(95.0);
        request.setLongitude(200.0);
        request.setMetadata(Map.of("speed", 45.2));

        assertThatThrownBy(() -> locationController.createForDriver(driverId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST");
    }

    private void createLocation(Long driverId, LocalDateTime timestamp) {
        Location location = new Location();
        location.setDriverId(driverId);
        location.setLatitude(30.0444);
        location.setLongitude(31.2357);
        location.setTimestamp(timestamp);
        location.setMetadata(Map.of("source", "test"));
        locationController.create(location);
    }
}



