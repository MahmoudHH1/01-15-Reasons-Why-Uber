package com.team01.uber.payment.saga;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentMethod;
import com.team01.uber.payment.model.PaymentStatus;
import com.team01.uber.payment.repository.PaymentRepository;
import com.team01.uber.payment.service.CacheInvalidationService;
import com.team01.uber.payment.strategy.ApprovedRefundResult;
import com.team01.uber.payment.strategy.BaseFareOnlyRefundStrategy;
import com.team01.uber.payment.strategy.DeniedRefundResult;
import com.team01.uber.payment.strategy.FullRefundWithSurgeStrategy;
import com.team01.uber.payment.strategy.NoRefundStrategy;
import com.team01.uber.payment.strategy.RefundContext;
import com.team01.uber.payment.strategy.RefundResult;
import com.team01.uber.payment.strategy.RefundStrategy;
import com.team01.uber.payment.strategy.RefundStrategySelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Ride lifecycle saga (§8.6) — payment-service participation A / B / C")
class SagaEndToEndIT {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private CacheInvalidationService cacheInvalidationService;

    private CapturingNotifier notifier;
    private RefundContext refundContext;
    private RefundStrategySelector strategySelector;

    @BeforeEach
    void setUp() {
        notifier = new CapturingNotifier();
        refundContext = new RefundContext(paymentRepository, notifier, cacheInvalidationService);
        strategySelector = new RefundStrategySelector();
    }

    @Nested
    @DisplayName("Scenario A — happy path end-to-end")
    class ScenarioAHappyPath {

        @Test
        @DisplayName("payment.completed flow: PENDING payment → COMPLETED, event payload carries paymentId/rideId/amount")
        void happyPathPaymentCompletes() {
            Payment pending = pendingPayment(10L, 1L, 200.0);
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

            Payment completed = simulateProcessPaymentSuccess(pending, "CREDIT_CARD", "4242");

            assertThat(completed.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            assertThat(completed.getTransactionDetails())
                    .containsEntry("gatewayResponse", "approved")
                    .containsEntry("cardLastFour", "4242");
            assertThat(notifier.lastEventType).isEqualTo("COMPLETED");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) notifier.lastPayload;
            assertThat(payload).containsEntry("rideId", 10L);
            assertThat(payload).containsEntry("amount", 200.0);
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("Scenario B — payment failure compensation via S5-F12 RefundStrategy")
    class ScenarioBCompensation {

        @Test
        @DisplayName("ride.cancelled (reason=payment_failed) on a COMPLETED payment → REFUNDED via FullRefundWithSurgeStrategy")
        void compensationCascadeRefundsViaStrategy() {
            Payment completed = completedPayment(10L, 1L, 200.0, surgeFee(60.0));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

            RefundSurgeRequest request = surgeRequest("payment_failed", true);
            RefundStrategy strategy = strategySelector.select(completed, request);
            assertThat(strategy).isInstanceOf(FullRefundWithSurgeStrategy.class);

            RefundResult result = strategy.calculateRefund(completed, request);
            assertThat(result).isInstanceOf(ApprovedRefundResult.class);
            assertThat(result.getAmount()).isEqualTo(200.0);
            assertThat(((ApprovedRefundResult) result).isSurgeIncluded()).isTrue();

            Payment refunded = result.apply(completed, request, refundContext, strategy.getClass().getSimpleName());

            assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refunded.getTransactionDetails())
                    .containsEntry("refundAmount", 200.0)
                    .containsEntry("refundSurgeIncluded", true)
                    .containsEntry("refundReason", "payment_failed");
            assertThat(notifier.lastEventType).isEqualTo("REFUNDED");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) notifier.lastPayload;
            assertThat(payload).containsEntry("strategyName", "FullRefundWithSurgeStrategy");
            assertThat(payload).containsEntry("refundAmount", 200.0);
            assertThat(payload).containsEntry("method", "CREDIT_CARD");
            verify(cacheInvalidationService).invalidateAllPaymentFeatureCaches(eq(completed.getId()));
        }

        @Test
        @DisplayName("rider-initiated cancel without surge consent → BaseFareOnlyRefundStrategy refunds amount minus surgeFee")
        void rideCancelWithoutSurgeUsesBaseFareOnly() {
            Payment completed = completedPayment(10L, 1L, 200.0, surgeFee(60.0));
            when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

            RefundSurgeRequest request = surgeRequest("user_requested", false);
            RefundStrategy strategy = strategySelector.select(completed, request);
            assertThat(strategy).isInstanceOf(BaseFareOnlyRefundStrategy.class);

            RefundResult result = strategy.calculateRefund(completed, request);
            assertThat(result.getAmount()).isEqualTo(140.0);

            Payment refunded = result.apply(completed, request, refundContext, strategy.getClass().getSimpleName());
            assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refunded.getTransactionDetails()).containsEntry("refundAmount", 140.0);
        }

        @Test
        @DisplayName("expired refund window → NoRefundStrategy → DeniedRefundResult, no payment.refunded published")
        void expiredWindowDeniesRefund() {
            Payment completed = completedPayment(10L, 1L, 200.0, surgeFee(60.0));
            completed.setCreatedAt(LocalDateTime.now().minusDays(2));

            RefundSurgeRequest request = surgeRequest("payment_failed", true);
            RefundStrategy strategy = strategySelector.select(completed, request);
            assertThat(strategy).isInstanceOf(NoRefundStrategy.class);

            RefundResult result = strategy.calculateRefund(completed, request);
            assertThat(result).isInstanceOf(DeniedRefundResult.class);

            assertThatThrownBy(() ->
                    result.apply(completed, request, refundContext, strategy.getClass().getSimpleName()))
                    .isInstanceOf(ResponseStatusException.class);

            assertThat(notifier.lastEventType).isEqualTo("REFUND_DENIED");
            assertThat(completed.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
            verify(paymentRepository, never()).save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("Scenario C — pre-check failure (no recent location ping) — no payment side-effects")
    class ScenarioCPreCheckFailure {

        @Test
        @DisplayName("ride never reached PAYMENT_PENDING → no PENDING payment, no payment.* event, ride stays IN_PROGRESS")
        void preCheckFailureProducesNoPaymentSideEffect() {
            when(paymentRepository.findByRideIdAndStatus(10L, PaymentStatus.PENDING))
                    .thenReturn(Optional.empty());

            Optional<Payment> pendingLookup =
                    paymentRepository.findByRideIdAndStatus(10L, PaymentStatus.PENDING);

            assertThat(pendingLookup).isEmpty();
            verify(paymentRepository, never()).save(any(Payment.class));
            assertThat(notifier.lastEventType).isNull();
        }

        @Test
        @DisplayName("ride.cancelled with no pre-existing payment → S5 consumer is no-op (state-based idempotency)")
        void rideCancelledWithoutPaymentIsNoOp() {
            when(paymentRepository.findByRideIdAndStatus(10L, PaymentStatus.COMPLETED))
                    .thenReturn(Optional.empty());
            when(paymentRepository.findByRideIdAndStatus(10L, PaymentStatus.PENDING))
                    .thenReturn(Optional.empty());

            Optional<Payment> completedLookup =
                    paymentRepository.findByRideIdAndStatus(10L, PaymentStatus.COMPLETED);
            Optional<Payment> pendingLookup =
                    paymentRepository.findByRideIdAndStatus(10L, PaymentStatus.PENDING);

            assertThat(completedLookup).isEmpty();
            assertThat(pendingLookup).isEmpty();
            verify(paymentRepository, never()).save(any(Payment.class));
            assertThat(notifier.lastEventType).isNull();
        }
    }

    private static Payment pendingPayment(long rideId, long userId, double amount) {
        Payment payment = new Payment();
        payment.setId(100L);
        payment.setRideId(rideId);
        payment.setUserId(userId);
        payment.setAmount(amount);
        payment.setMethod(PaymentMethod.CREDIT_CARD);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setCreatedAt(LocalDateTime.now());
        return payment;
    }

    private static Payment completedPayment(long rideId, long userId, double amount, Map<String, Object> details) {
        Payment payment = pendingPayment(rideId, userId, amount);
        payment.setStatus(PaymentStatus.COMPLETED);
        payment.setTransactionDetails(new HashMap<>(details));
        return payment;
    }

    private static Map<String, Object> surgeFee(double surgeFee) {
        Map<String, Object> details = new HashMap<>();
        details.put("surgeFee", surgeFee);
        return details;
    }

    private static RefundSurgeRequest surgeRequest(String reason, boolean refundSurge) {
        RefundSurgeRequest request = new RefundSurgeRequest();
        request.setReason(reason);
        request.setRefundSurge(refundSurge);
        return request;
    }

    private Payment simulateProcessPaymentSuccess(Payment pending, String method, String cardLastFour) {
        pending.setStatus(PaymentStatus.COMPLETED);
        Map<String, Object> details = pending.getTransactionDetails() == null
                ? new HashMap<>()
                : new HashMap<>(pending.getTransactionDetails());
        details.put("gatewayResponse", "approved");
        details.put("cardLastFour", cardLastFour);
        details.put("surgeFee", pending.getAmount() * 0.15);
        pending.setTransactionDetails(details);
        Payment saved = paymentRepository.save(pending);
        notifier.notify("COMPLETED", Map.of(
                "paymentId", saved.getId(),
                "rideId", saved.getRideId(),
                "amount", saved.getAmount(),
                "method", method
        ));
        return saved;
    }

    private static class CapturingNotifier implements RefundContext.RefundEventNotifier {
        String lastEventType;
        Object lastPayload;

        @Override
        public void notify(String eventType, Object payload) {
            this.lastEventType = eventType;
            this.lastPayload = payload;
        }
    }
}
