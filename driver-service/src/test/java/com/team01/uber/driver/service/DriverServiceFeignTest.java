package com.team01.uber.driver.service;

import com.team01.uber.driver.adapter.ElasticsearchHitAdapter;
import com.team01.uber.driver.client.RideClient;
import com.team01.uber.driver.cache.CacheInvalidator;
import com.team01.uber.driver.messaging.DriverEventPublisher;
import com.team01.uber.driver.model.Driver;
import com.team01.uber.driver.model.DriverStatus;
import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.repository.DriverRepository;
import com.team01.uber.driver.repository.DriverSearchEsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus item (1) — Unit tests for service business logic with mocked Feign clients.
 *
 * Focus: driver-service's S2-F4 updateAvailability (the spec-named Feign hot path).
 * RideClient.countActiveRidesForDriver gates the OFFLINE transition: if active
 * rides exist, going OFFLINE must be refused (§4 / §8.3 saga consistency).
 *
 * All collaborators (RideClient, repositories, ES, Redis, publishers) are
 * @Mock'd — no Spring context, no HTTP, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DriverService.updateAvailability — Feign-gated OFFLINE transition (unit)")
class DriverServiceFeignTest {

    @Mock private DriverRepository driverRepository;
    @Mock private MongoEventLogger mongoEventLogger;
    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private CacheInvalidator cacheInvalidator;
    @Mock private DriverSearchEsRepository searchEsRepository;
    @Mock private ElasticsearchHitAdapter searchHitAdapter;
    @Mock private DriverIndexerService driverIndexerService;
    @Mock private CacheManager cacheManager;
    @Mock private RideClient rideClient;
    @Mock private DriverEventPublisher driverEventPublisher;

    private DriverService driverService;

    @BeforeEach
    void setUp() {
        driverService = new DriverService(
                driverRepository, mongoEventLogger, redisTemplate, cacheInvalidator,
                searchEsRepository, searchHitAdapter, driverIndexerService, cacheManager,
                rideClient, driverEventPublisher);
    }

    @Test
    @DisplayName("§8.3: going OFFLINE with 0 active rides → status persisted, driver.status-changed published")
    void updateAvailability_offlineWithZeroActiveRides_persists() {
        Driver d = driver(5L, DriverStatus.BUSY);
        when(driverRepository.findById(5L)).thenReturn(Optional.of(d));
        when(rideClient.countActiveRidesForDriver(5L)).thenReturn(0L);
        when(driverRepository.save(any(Driver.class))).thenAnswer(inv -> inv.getArgument(0));

        driverService.updateAvailability(5L, DriverStatus.OFFLINE);

        assertThat(d.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        verify(driverEventPublisher).publishStatusChanged(5L, "BUSY", "OFFLINE");
    }

    @Test
    @DisplayName("§8.3: going OFFLINE with active rides → 400, driver row never saved, no event")
    void updateAvailability_offlineWithActiveRides_throws400_noPublish() {
        Driver d = driver(5L, DriverStatus.BUSY);
        when(driverRepository.findById(5L)).thenReturn(Optional.of(d));
        when(rideClient.countActiveRidesForDriver(5L)).thenReturn(1L);

        assertThatThrownBy(() ->
                driverService.updateAvailability(5L, DriverStatus.OFFLINE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot go OFFLINE");

        verify(driverRepository, never()).save(any());
        verify(driverEventPublisher, never()).publishStatusChanged(anyLong(), any(), any());
    }

    @Test
    @DisplayName("§8.3: AVAILABLE / BUSY transitions do not consult Feign — purely local")
    void updateAvailability_availableOrBusy_neverHitsFeign() {
        Driver d = driver(7L, DriverStatus.AVAILABLE);
        when(driverRepository.findById(7L)).thenReturn(Optional.of(d));
        when(driverRepository.save(any(Driver.class))).thenAnswer(inv -> inv.getArgument(0));

        driverService.updateAvailability(7L, DriverStatus.BUSY);

        assertThat(d.getStatus()).isEqualTo(DriverStatus.BUSY);
        verify(rideClient, never()).countActiveRidesForDriver(anyLong());
        verify(driverEventPublisher).publishStatusChanged(7L, "AVAILABLE", "BUSY");
    }

    @Test
    @DisplayName("§8.3 graceful degradation: wrapper hides Feign failure → 0 active rides, OFFLINE persists")
    void updateAvailability_feignFailure_treatedAsZeroActive() {
        // The RideClient wrapper hides Feign/CB details from DriverService. The wrapper's
        // FeignException handling is covered separately in RideClientCircuitBreakerTest;
        // here we model the wrapper's externally-visible "no active rides" outcome.
        Driver d = driver(9L, DriverStatus.BUSY);
        when(driverRepository.findById(9L)).thenReturn(Optional.of(d));
        when(rideClient.countActiveRidesForDriver(9L)).thenReturn(0L);
        when(driverRepository.save(any(Driver.class))).thenAnswer(inv -> inv.getArgument(0));

        driverService.updateAvailability(9L, DriverStatus.OFFLINE);

        assertThat(d.getStatus()).isEqualTo(DriverStatus.OFFLINE);
        verify(driverEventPublisher).publishStatusChanged(9L, "BUSY", "OFFLINE");
    }

    private static Driver driver(Long id, DriverStatus status) {
        Driver d = new Driver();
        d.setId(id);
        d.setStatus(status);
        return d;
    }
}
