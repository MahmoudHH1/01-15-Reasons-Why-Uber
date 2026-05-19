package com.team01.uber.location.controller;

import com.team01.uber.location.repository.LocationEventRepository;
import com.team01.uber.location.service.LocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class LocationControllerTest {

    @Mock
    private LocationService locationService;

    @Mock
    private LocationEventRepository locationEventRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LocationController(locationService, locationEventRepository))
                .build();
    }

    @Test
    void stationary_withoutPaginationParams_usesDefaults() throws Exception {
        when(locationService.findStationaryDrivers(anyDouble(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/locations/stationary")
                        .param("maxSpeed", "0")
                        .param("sinceMinutes", "30"))
                .andExpect(status().isOk());

        verify(locationService).findStationaryDrivers(0.0, 30, 0, 20);
    }

    @Test
    void nearby_withoutPaginationParams_usesDefaults() throws Exception {
        when(locationService.findNearbyDrivers(anyDouble(), anyDouble(), anyDouble(), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/locations/nearby")
                        .param("lat", "30.0")
                        .param("lon", "31.0")
                        .param("radiusKm", "5.0"))
                .andExpect(status().isOk());

        verify(locationService).findNearbyDrivers(30.0, 31.0, 5.0, 0, 20);
    }
}
