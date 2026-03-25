package com.team01.uber.location;

import com.team01.uber.location.controller.LocationController;
import com.team01.uber.location.dto.PurgeResponse;
import com.team01.uber.location.model.Location;
import com.team01.uber.location.repository.LocationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:locationdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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

    @Test
    void purgeOlderThanDaysDeletesOnlyOldLocations() {
        locationRepository.deleteAll();

        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 7; i++) {
            locationRepository.save(buildLocation(now.minusDays(60)));
        }
        for (int i = 0; i < 3; i++) {
            locationRepository.save(buildLocation(now));
        }

        PurgeResponse response = locationController.purgeOldLocations(30).getBody();

        assertThat(response).isNotNull();
        long deletedCount = response.getDeletedCount();

        assertThat(deletedCount).isEqualTo(7);

        assertThat(locationRepository.count()).isEqualTo(3);
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

}
