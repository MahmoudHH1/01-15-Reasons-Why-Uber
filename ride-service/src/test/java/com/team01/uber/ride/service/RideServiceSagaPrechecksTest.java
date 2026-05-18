package com.team01.uber.ride.service;

import com.team01.uber.contracts.dto.DriverAvailabilityDTO;
import com.team01.uber.contracts.dto.LocationDTO;
import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.contracts.feign.DriverServiceClient;
import com.team01.uber.contracts.feign.LocationServiceClient;
import com.team01.uber.contracts.feign.UserServiceClient;
import com.team01.uber.ride.enums.RideStatus;
import com.team01.uber.ride.messaging.publishers.RideEventPublisherService;
import com.team01.uber.ride.model.Ride;
import com.team01.uber.ride.observer.RideEventPublisher;
import com.team01.uber.ride.repository.DriverNodeRepository;
import com.team01.uber.ride.repository.RideRepository;
import com.team01.uber.ride.repository.RideStopRepository;
import com.team01.uber.ride.repository.UserNodeRepository;
import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus — Unit tests for service business logic with @MockBean (Mockito @Mock)
 * on all Feign clients.
 *
 * Focus: RideService.completeRide (the S3-F4 saga trigger, §8.3 pre-saga checks).
 *
 * Each pre-check is exercised independently:
 *   1. user-service.getUser            -> status must be ACTIVE
 *   2. driver-service.getDriverAvailability -> status must be BUSY
 *   3. location-service.getRecentLocationForDriver -> 200 with last-5-min ping
 *
 * Plus the publish-after-commit happy path: all three pass -> ride is saved,
 * ride.completed is published. No transitive integration assumed — every Feign
 * client is fully mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RideService.completeRide — §8.3 pre-saga Feign checks (unit)")
class RideServiceSagaPrechecksTest {

    @Mock private RideRepository rideRepository;
    @Mock private RideStopRepository rideStopRepository;
    @Mock private UserNodeRepository userNodeRepository;
    @Mock private DriverNodeRepository driverNodeRepository;
    @Mock private RideEventPublisher rideEventPublisher;
    @Mock private DriverServiceClient driverServiceClient;
    @Mock private UserServiceClient userServiceClient;
    @Mock private LocationServiceClient locationServiceClient;
    @Mock private RideEventPublisherService producer;

    @InjectMocks
    private RideService rideService;

    private Ride inProgressRide;

    @BeforeEach
    void setUp() {
        inProgressRide = new Ride();
        inProgressRide.setId(10L);
        inProgressRide.setUserId(1L);
        inProgressRide.setDriverId(5L);
        inProgressRide.setStatus(RideStatus.IN_PROGRESS);
        inProgressRide.setFare(50.0);
        inProgressRide.setPickupLatitude(30.04);
        inProgressRide.setPickupLongitude(31.23);
        inProgressRide.setDropoffLatitude(30.05);
        inProgressRide.setDropoffLongitude(31.24);
    }

    @Test
    @DisplayName("happy path: all 3 pre-checks pass -> exact ride saved with COMPLETED+completedAt; published instance matches saved; §2.11 commit-then-publish order")
    void happyPath_allPrechecksPass_publishesRideCompleted() {
        when(rideRepository.findById(10L)).thenReturn(Optional.of(inProgressRide));
        when(userServiceClient.getUser(1L)).thenReturn(activeUser(1L));
        when(driverServiceClient.getDriverAvailability(5L)).thenReturn(busyDriver(5L));
        when(locationServiceClient.getRecentLocationForDriver(5L)).thenReturn(recentPing(5L));
        when(rideRepository.save(any(Ride.class))).thenAnswer(inv -> inv.getArgument(0));

        Ride result = rideService.completeRide(10L);

        ArgumentCaptor<Ride> savedCaptor = ArgumentCaptor.forClass(Ride.class);
        verify(rideRepository).save(savedCaptor.capture());
        Ride saved = savedCaptor.getValue();
        assertThat(saved.getId()).isEqualTo(10L);
        assertThat(saved.getStatus()).isEqualTo(RideStatus.COMPLETED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getDriverId()).isEqualTo(5L);

        ArgumentCaptor<Ride> publishedCaptor = ArgumentCaptor.forClass(Ride.class);
        verify(producer).publishRideCompleted(publishedCaptor.capture());
        assertThat(publishedCaptor.getValue())
                .as("§2.11: the published event must reference the same ride state that was just committed")
                .isSameAs(saved);

        assertThat(result).isSameAs(saved);

        InOrder inOrder = inOrder(userServiceClient, driverServiceClient, locationServiceClient,
                rideRepository, producer);
        inOrder.verify(userServiceClient).getUser(1L);
        inOrder.verify(driverServiceClient).getDriverAvailability(5L);
        inOrder.verify(locationServiceClient).getRecentLocationForDriver(5L);
        inOrder.verify(rideRepository).save(any(Ride.class));
        inOrder.verify(producer).publishRideCompleted(any(Ride.class));
    }

    @Test
    @DisplayName("§8.3 pre-check 1: user DEACTIVATED -> 400, no save, no event published")
    void preCheck1_userDeactivated_throws400_andNoPublish() {
        when(rideRepository.findById(10L)).thenReturn(Optional.of(inProgressRide));
        when(userServiceClient.getUser(1L)).thenReturn(new UserDTO(1L, "John", "u@x.io", "RIDER", "DEACTIVATED"));

        assertThatThrownBy(() -> rideService.completeRide(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User account is not active");
        verify(rideRepository, never()).save(any());
        verify(producer, never()).publishRideCompleted(any());
        verify(driverServiceClient, never()).getDriverAvailability(anyLong());
        verify(locationServiceClient, never()).getRecentLocationForDriver(anyLong());
    }

    @Test
    @DisplayName("§8.3 pre-check 1: user 404 from Feign -> 400 'User not found'; downstream Feign + publish must be skipped")
    void preCheck1_userNotFound_throws400() {
        when(rideRepository.findById(10L)).thenReturn(Optional.of(inProgressRide));
        when(userServiceClient.getUser(1L)).thenThrow(notFound());

        assertThatThrownBy(() -> rideService.completeRide(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User not found");
        verify(rideRepository, never()).save(any());
        verify(producer, never()).publishRideCompleted(any());
        verify(driverServiceClient, never()).getDriverAvailability(anyLong());
        verify(locationServiceClient, never()).getRecentLocationForDriver(anyLong());
    }

    @Test
    @DisplayName("§8.3 pre-check 2: driver not BUSY -> 400, no save, no publish, location check skipped")
    void preCheck2_driverNotBusy_throws400() {
        when(rideRepository.findById(10L)).thenReturn(Optional.of(inProgressRide));
        when(userServiceClient.getUser(1L)).thenReturn(activeUser(1L));
        when(driverServiceClient.getDriverAvailability(5L))
                .thenReturn(new DriverAvailabilityDTO("AVAILABLE"));

        assertThatThrownBy(() -> rideService.completeRide(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Driver is not currently active");
        verify(rideRepository, never()).save(any());
        verify(producer, never()).publishRideCompleted(any());
        verify(locationServiceClient, never()).getRecentLocationForDriver(anyLong());
    }

    @Test
    @DisplayName("§8.3 pre-check 3 (scenario C): location 404 (stale ping) -> 400 'Driver not actively tracked'")
    void preCheck3_locationStale_throws400_perScenarioC() {
        when(rideRepository.findById(10L)).thenReturn(Optional.of(inProgressRide));
        when(userServiceClient.getUser(1L)).thenReturn(activeUser(1L));
        when(driverServiceClient.getDriverAvailability(5L)).thenReturn(busyDriver(5L));
        when(locationServiceClient.getRecentLocationForDriver(5L)).thenThrow(notFound());

        assertThatThrownBy(() -> rideService.completeRide(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Driver not actively tracked");
        verify(rideRepository, never()).save(any());
        verify(producer, never()).publishRideCompleted(any());
    }

    @Test
    @DisplayName("ride status != IN_PROGRESS -> 400 before any Feign call")
    void rideStatusNotInProgress_throws400_beforeFeignCalls() {
        inProgressRide.setStatus(RideStatus.COMPLETED);
        when(rideRepository.findById(10L)).thenReturn(Optional.of(inProgressRide));

        assertThatThrownBy(() -> rideService.completeRide(10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("IN_PROGRESS");
        verify(userServiceClient, never()).getUser(anyLong());
        verify(driverServiceClient, never()).getDriverAvailability(anyLong());
        verify(locationServiceClient, never()).getRecentLocationForDriver(anyLong());
    }

    private static UserDTO activeUser(Long id) {
        return new UserDTO(id, "John", "u@x.io", "RIDER", "ACTIVE");
    }

    private static DriverAvailabilityDTO busyDriver(Long id) {
        return new DriverAvailabilityDTO("BUSY");
    }

    private static LocationDTO recentPing(Long driverId) {
        return new LocationDTO(driverId, 30.04, 31.23, java.time.LocalDateTime.now(), 0.0);
    }

    private static FeignException notFound() {
        Request request = Request.create(
                Request.HttpMethod.GET, "/test", Map.of(),
                Request.Body.empty(), new RequestTemplate());
        return new FeignException.NotFound("not found", request, new byte[0], Map.of());
    }
}
