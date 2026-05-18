package com.team01.uber.user.service;

import com.team01.uber.user.client.PaymentClient;
import com.team01.uber.user.client.RideClient;
import com.team01.uber.user.dto.TopRiderDTO;
import com.team01.uber.user.messaging.publishers.UserEventPublisher;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.observer.MongoEventLogger;
import com.team01.uber.user.repository.AuthEventRepository;
import com.team01.uber.user.repository.SavedAddressRepository;
import com.team01.uber.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus item (1) — Unit tests for service business logic with mocked Feign clients.
 *
 * Focus: user-service's three Feign-using methods:
 *   - S1-F6 getTopRiders        → PaymentClient.getUserTotalPayments (per-user fan-out)
 *   - S1-F9 findUsersByLanguage → RideClient.getCompletedRideCount (per-user fan-out)
 *   - S1-F4 deactivateUser      → RideClient.getActiveRideCount (active-rides gate)
 *
 * UserService talks to the local circuit-breaker wrappers, not the raw contracts clients,
 * so we mock the wrappers here. Wrapper-level fallback / FeignException handling is covered
 * separately in RideClientCircuitBreakerTest.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — Feign-using methods (unit, all Feign clients mocked)")
class UserServiceFeignTest {

    @Mock private UserRepository userRepository;
    @Mock private SavedAddressRepository savedAddressRepository;
    @Mock private MongoEventLogger mongoEventLogger;
    @Mock private AuthEventRepository authEventRepository;
    @Mock private UserEventPublisher userEventPublisher;
    @Mock private RideClient rideClient;
    @Mock private PaymentClient paymentClient;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository, savedAddressRepository, mongoEventLogger,
                authEventRepository, userEventPublisher, rideClient, paymentClient);
    }

    // ───────────── S1-F6 getTopRiders ─────────────

    @Test
    @DisplayName("S1-F6: aggregates per-user totals via payment-service, sorts desc, applies caller limit")
    void getTopRiders_aggregatesFromPaymentServiceFeign_sortsAndLimits() {
        when(userRepository.findCandidateUsersCapped()).thenReturn(List.of(
                user(1L, "Alice"), user(2L, "Bob"), user(3L, "Carol")));

        when(paymentClient.getUserTotalPayments(eq(1L), anyString(), anyString()))
                .thenReturn(BigDecimal.valueOf(100.00));
        when(paymentClient.getUserTotalPayments(eq(2L), anyString(), anyString()))
                .thenReturn(BigDecimal.valueOf(500.00));
        when(paymentClient.getUserTotalPayments(eq(3L), anyString(), anyString()))
                .thenReturn(BigDecimal.valueOf(300.00));

        List<TopRiderDTO> top = userService.getTopRiders("2026-03-01", "2026-03-31", 2);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).userId()).isEqualTo(2L);
        assertThat(top.get(0).totalSpent()).isEqualTo(500.00);
        assertThat(top.get(1).userId()).isEqualTo(3L);
        verify(paymentClient, times(3)).getUserTotalPayments(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("S1-F6: user with zero payments is excluded from result")
    void getTopRiders_zeroSpendUserExcluded() {
        when(userRepository.findCandidateUsersCapped()).thenReturn(List.of(user(1L, "Zero")));
        when(paymentClient.getUserTotalPayments(eq(1L), anyString(), anyString()))
                .thenReturn(BigDecimal.ZERO);

        List<TopRiderDTO> top = userService.getTopRiders("2026-03-01", "2026-03-31", 10);

        assertThat(top).isEmpty();
    }

    @Test
    @DisplayName("S1-F6: invalid date format → 400, no Feign call")
    void getTopRiders_invalidDate_throws400_noFeign() {
        assertThatThrownBy(() ->
                userService.getTopRiders("not-a-date", "2026-03-31", 10))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid date format");
        verify(paymentClient, never()).getUserTotalPayments(anyLong(), anyString(), anyString());
    }

    // ───────────── S1-F9 findUsersByLanguageWithMinRides ─────────────

    @Test
    @DisplayName("S1-F9: returns only users whose Feign-reported completed count >= minRides")
    void findUsersByLanguage_filtersByMinRides() {
        when(userRepository.findByPreferenceCapped("language", "ar"))
                .thenReturn(List.of(user(1L, "A"), user(2L, "B"), user(3L, "C")));

        when(rideClient.getCompletedRideCount(1L)).thenReturn(5L);
        when(rideClient.getCompletedRideCount(2L)).thenReturn(2L);
        when(rideClient.getCompletedRideCount(3L)).thenReturn(10L);

        List<User> qualified = userService.findUsersByLanguageWithMinRides("ar", 3);

        assertThat(qualified).extracting(User::getId).containsExactlyInAnyOrder(1L, 3L);
        verify(rideClient, times(3)).getCompletedRideCount(anyLong());
    }

    @Test
    @DisplayName("S1-F9: blank lang → 400, no Feign call")
    void findUsersByLanguage_blankLang_throws400() {
        assertThatThrownBy(() ->
                userService.findUsersByLanguageWithMinRides("  ", 1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("lang must not be blank");
        verify(rideClient, never()).getCompletedRideCount(anyLong());
    }

    @Test
    @DisplayName("S1-F9: wrapper-swallowed 404 (returns 0) means that user is silently excluded, others still evaluated")
    void findUsersByLanguage_wrapperReturnsZero_userExcluded_continuesOthers() {
        when(userRepository.findByPreferenceCapped("language", "en"))
                .thenReturn(List.of(user(1L, "A"), user(2L, "B")));

        // RideClient wrapper catches FeignException.NotFound and returns 0L — same observable effect at this layer.
        when(rideClient.getCompletedRideCount(1L)).thenReturn(0L);
        when(rideClient.getCompletedRideCount(2L)).thenReturn(7L);

        List<User> qualified = userService.findUsersByLanguageWithMinRides("en", 5);

        assertThat(qualified).hasSize(1);
        assertThat(qualified.get(0).getId()).isEqualTo(2L);
    }

    // ───────────── S1-F4 deactivateUser ─────────────

    @Test
    @DisplayName("S1-F4: 0 active rides → status=DEACTIVATED, user.deactivated published")
    void deactivateUser_noActiveRides_setsDeactivated_andPublishes() {
        User u = user(10L, "X");
        u.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(10L)).thenReturn(Optional.of(u));
        when(rideClient.getActiveRideCount(10L)).thenReturn(0);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.deactivateUser(10L);

        assertThat(saved.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        verify(userEventPublisher).publishUserDeactivated(10L);
    }

    @Test
    @DisplayName("S1-F4: active rides > 0 → 400, no save, no publish")
    void deactivateUser_hasActiveRides_throws400_noSideEffects() {
        User u = user(11L, "Y");
        u.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(11L)).thenReturn(Optional.of(u));
        when(rideClient.getActiveRideCount(11L)).thenReturn(2);

        assertThatThrownBy(() -> userService.deactivateUser(11L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active rides");
        verify(userRepository, never()).save(any());
        verify(userEventPublisher, never()).publishUserDeactivated(anyLong());
    }

    @Test
    @DisplayName("S1-F4: already DEACTIVATED → idempotent 200, no Feign call, no event")
    void deactivateUser_alreadyDeactivated_isNoOp() {
        User u = user(12L, "Z");
        u.setStatus(UserStatus.DEACTIVATED);
        when(userRepository.findById(12L)).thenReturn(Optional.of(u));

        User saved = userService.deactivateUser(12L);

        assertThat(saved.getStatus()).isEqualTo(UserStatus.DEACTIVATED);
        verify(rideClient, never()).getActiveRideCount(anyLong());
        verify(userEventPublisher, never()).publishUserDeactivated(anyLong());
        verify(userRepository, never()).save(any());
    }

    private static User user(Long id, String name) {
        User u = new User();
        u.setId(id);
        u.setName(name);
        u.setStatus(UserStatus.ACTIVE);
        u.setPreferences(Map.of());
        return u;
    }
}
