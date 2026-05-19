package com.team01.uber.location.service;

import com.team01.uber.contracts.dto.DriverDTO;
import com.team01.uber.location.client.DriverClient;
import com.team01.uber.location.dto.NearbyDriverDTO;
import com.team01.uber.location.dto.StationaryDriverDTO;
import com.team01.uber.location.repository.LocationRepository;
import com.team01.uber.location.repository.LocationTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class LocationServiceTest {

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private LocationTrackingEventRepository trackingRepository;
    @Mock
    private RedisTemplate redisTemplate;
    @Mock
    private DriverClient driverClient;
    @Mock
    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;

    private LocationService locationService;

    @BeforeEach
    void setUp() {
        locationService = new LocationService(
                locationRepository,
                trackingRepository,
                redisTemplate,
                new ArrayList<>(),
                driverClient,
                rabbitTemplate
        );
    }

    @Test
    void testFindNearbyDrivers_FiltersAvailable() {
        // Setup
        List<Object[]> localResults = new ArrayList<>();
        localResults.add(new Object[]{1L, 30.04, 31.23, 1.2}); // Driver 1
        localResults.add(new Object[]{2L, 30.05, 31.24, 2.5}); // Driver 2

        when(locationRepository.findNearbyDriversLocal(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(localResults);

        // Driver 1 is AVAILABLE
        when(driverClient.getDriver(1L))
                .thenReturn(new DriverDTO(1L, "Driver A", "AVAILABLE", Map.of()));
        // Driver 2 is BUSY
        when(driverClient.getDriver(2L))
                .thenReturn(new DriverDTO(2L, "Driver B", "BUSY", Map.of()));

        // Execute
        List<NearbyDriverDTO> results = locationService.findNearbyDrivers(30.0, 31.0, 5.0, 0, 20);

        // Verify
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getDriverId());
        assertEquals("Driver A", results.get(0).getDriverName());
    }

    @Test
    void testFindStationaryDrivers_EnrichesNames() {
        // Setup
        List<Object[]> localResults = new ArrayList<>();
        localResults.add(new Object[]{1L, 30.04, 31.23, 0.0, LocalDateTime.now()});

        when(locationRepository.findStationaryDriversLocal(anyDouble(), any(LocalDateTime.class)))
                .thenReturn(localResults);

        when(driverClient.getDriver(1L))
                .thenReturn(new DriverDTO(1L, "Driver A", "AVAILABLE", Map.of()));

        // Execute
        List<StationaryDriverDTO> results = locationService.findStationaryDrivers(1.0, 30, 0, 20);

        // Verify
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getDriverId());
        assertEquals("Driver A", results.get(0).getDriverName());
    }
}
