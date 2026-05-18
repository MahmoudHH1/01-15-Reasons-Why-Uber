package com.team01.uber.payment.service;

import com.team01.uber.contracts.dto.UserDTO;
import com.team01.uber.payment.adapter.MongoDocumentAdapter;
import com.team01.uber.payment.client.DriverClient;
import com.team01.uber.payment.client.RideClient;
import com.team01.uber.payment.client.UserClient;
import com.team01.uber.payment.dto.UserPaymentSummaryDTO;
import com.team01.uber.payment.messaging.PaymentEventPublisher;
import com.team01.uber.payment.repository.PaymentRepository;
import com.team01.uber.payment.strategy.RefundStrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus item (1) — Unit tests for service business logic with mocked Feign clients.
 *
 * Focus: PaymentService.getUserPaymentSummary (S5-F9) — the Feign-gated read path.
 * §2.10 caller existence: if user-service Feign returns 404, payment-service must throw 404.
 *
 * All Feign clients (UserClient, RideClient, DriverClient) are
 * @Mock'd — no Spring context, no HTTP, no DB.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService.getUserPaymentSummary — Feign-gated read (unit)")
class PaymentServiceFeignTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundStrategySelector strategySelector;
    @Mock private CacheInvalidationService cacheInvalidationService;
    @Mock private MongoTemplate mongoTemplate;
    @Mock private MongoDocumentAdapter mongoDocumentAdapter;
    @Mock private PaymentEventPublisher paymentEventPublisher;
    @Mock private UserClient userClient;
    @Mock private RideClient rideClient;
    @Mock private DriverClient driverClient;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, strategySelector, cacheInvalidationService,
                mongoTemplate, mongoDocumentAdapter, paymentEventPublisher,
                userClient, rideClient, driverClient);
    }

    @Test
    @DisplayName("S5-F9 happy path: user exists, aggregator returns breakdown across payment methods")
    void getUserPaymentSummary_happyPath_returnsAggregatedBreakdown() {
        Long userId = 10L;
        when(userClient.getUser(userId)).thenReturn(activeUser(userId));
        when(paymentRepository.findCompletedPaymentsSummaryByUser(userId)).thenReturn(List.of(
                new Object[] { "CREDIT_CARD", 3L, 195.0 },
                new Object[] { "CASH",        1L, 50.0  },
                new Object[] { "WALLET",      2L, 60.0  }));

        UserPaymentSummaryDTO summary = paymentService.getUserPaymentSummary(userId);

        assertThat(summary.getUserId()).isEqualTo(userId);
        assertThat(summary.getTotalPayments()).isEqualTo(6L);
        assertThat(summary.getTotalAmount()).isEqualTo(305.0);
        assertThat(summary.getMethodBreakdown())
                .containsEntry("CREDIT_CARD", 195.0)
                .containsEntry("CASH", 50.0)
                .containsEntry("WALLET", 60.0);
    }

    @Test
    @DisplayName("S5-F9: empty result still returns 200 with totals=0 (per §7 spec wording)")
    void getUserPaymentSummary_noPaymentsButUserExists_returnsEmptyTotals() {
        when(userClient.getUser(20L)).thenReturn(activeUser(20L));
        when(paymentRepository.findCompletedPaymentsSummaryByUser(20L)).thenReturn(List.of());

        UserPaymentSummaryDTO summary = paymentService.getUserPaymentSummary(20L);

        assertThat(summary.getTotalPayments()).isZero();
        assertThat(summary.getTotalAmount()).isZero();
        assertThat(summary.getMethodBreakdown()).isEmpty();
    }

    @Test
    @DisplayName("§2.10: wrapper-converted 404 from user-service → 404 'User not found', no DB query")
    void getUserPaymentSummary_userNotFound_throws404_noDbQuery() {
        // UserClient wrapper converts FeignException.NotFound → ResponseStatusException(404).
        when(userClient.getUser(999L)).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        assertThatThrownBy(() -> paymentService.getUserPaymentSummary(999L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User not found");

        verify(paymentRepository, never()).findCompletedPaymentsSummaryByUser(anyLong());
    }

    @Test
    @DisplayName("§2.10: wrapper-converted 503 from user-service → 503, no DB query")
    void getUserPaymentSummary_userServiceUnavailable_throws503_noDbQuery() {
        // UserClient wrapper converts other FeignException → ResponseStatusException(503).
        when(userClient.getUser(40L)).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "User service unavailable"));

        assertThatThrownBy(() -> paymentService.getUserPaymentSummary(40L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("User service unavailable");

        verify(paymentRepository, never()).findCompletedPaymentsSummaryByUser(anyLong());
    }

    private static UserDTO activeUser(Long id) {
        return new UserDTO(id, "Tester", "u@x.io", "RIDER", "ACTIVE");
    }
}
