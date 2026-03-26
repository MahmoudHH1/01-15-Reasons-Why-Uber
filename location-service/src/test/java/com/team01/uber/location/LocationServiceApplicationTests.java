package com.team01.uber.location;

import com.team01.uber.location.controller.LocationController;
import com.team01.uber.location.model.BatchLocationRequest;
import com.team01.uber.location.model.BatchLocationResponse;
import com.team01.uber.location.model.PurgeResponse;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:locationdb2;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR(255);DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class LocationServiceApplicationTests {

    @Autowired
    private LocationController locationController;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        locationRepository.deleteAll();
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS drivers (id BIGINT PRIMARY KEY)");
        jdbcTemplate.execute("DELETE FROM drivers");
        jdbcTemplate.execute("INSERT INTO drivers (id) VALUES (1)");
    }

    @Test
    void purgeOlderThanDaysDeletesOnlyOldLocations() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 7; i++) {
            locationRepository.save(buildLocation(now.minusDays(60)));
        }
        for (int i = 0; i < 3; i++) {
            locationRepository.save(buildLocation(now));
        }

        PurgeResponse response = locationController.purgeOldLocations(30).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getDeletedCount()).isEqualTo(7);
        assertThat(locationRepository.count()).isEqualTo(3);
    }

    @Test
    void batchUpdateCreatesLocations() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(1L);
        request.setLocations(List.of(
                buildLocationEntry(30.0, 31.0),
                buildLocationEntry(31.0, 32.0),
                buildLocationEntry(32.0, 33.0)
        ));

        ResponseEntity<BatchLocationResponse> response = locationController.batchUpdate(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCount()).isEqualTo(3);
        assertThat(locationRepository.count()).isEqualTo(3);
    }

    @Test
    void batchUpdateReturnsBadRequestWhenDriverIdIsNull() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(null);
        request.setLocations(List.of(buildLocationEntry(30.0, 31.0)));

        assertThatThrownBy(() -> locationController.batchUpdate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void batchUpdateReturnsBadRequestWhenLocationsIsNull() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(1L);
        request.setLocations(null);

        assertThatThrownBy(() -> locationController.batchUpdate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void batchUpdateReturnsBadRequestWhenLocationsIsEmpty() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(1L);
        request.setLocations(List.of());

        assertThatThrownBy(() -> locationController.batchUpdate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void batchUpdateReturnsBadRequestWhenLatitudeIsInvalid() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(1L);
        request.setLocations(List.of(buildLocationEntry(91.0, 31.0)));

        assertThatThrownBy(() -> locationController.batchUpdate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void batchUpdateReturnsBadRequestWhenLongitudeIsInvalid() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(1L);
        request.setLocations(List.of(buildLocationEntry(30.0, 181.0)));

        assertThatThrownBy(() -> locationController.batchUpdate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void batchUpdateReturnsNotFoundWhenDriverDoesNotExist() {
        BatchLocationRequest request = new BatchLocationRequest();
        request.setDriverId(999L);
        request.setLocations(List.of(buildLocationEntry(30.0, 31.0)));

        assertThatThrownBy(() -> locationController.batchUpdate(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private Location buildLocation(LocalDateTime timestamp) {
        Location location = new Location();
        location.setDriverId(1L);
        location.setLatitude(30.0444);
        location.setLongitude(31.2357);
        location.setTimestamp(timestamp);
        location.setMetadata(Map.of("source", "test"));
        return location;
    }

    private Location buildLocationEntry(double lat, double lon) {
        Location location = new Location();
        location.setLatitude(lat);
        location.setLongitude(lon);
        return location;
    }
}
