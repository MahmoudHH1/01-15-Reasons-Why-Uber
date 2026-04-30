package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public abstract class ApprovedRefundStrategy implements RefundStrategy {

    @Override
    public Payment execute(Payment payment, RefundSurgeRequest request, RefundContext ctx) {
        double refundAmount = computeRefundAmount(payment);
        boolean surgeIncluded = isSurgeIncluded();

        payment.setStatus(PaymentStatus.REFUNDED);
        if (payment.getTransactionDetails() == null) {
            payment.setTransactionDetails(new HashMap<>());
        }
        payment.getTransactionDetails().put("refundAmount", refundAmount);
        payment.getTransactionDetails().put("refundSurgeIncluded", surgeIncluded);
        payment.getTransactionDetails().put("refundReason", request.getReason());
        payment.getTransactionDetails().put("refundedAt", LocalDateTime.now().toString());

        Payment saved = ctx.repository.save(payment);

        ctx.notifier.notify("REFUNDED", Map.of(
                "paymentId", saved.getId(),
                "method", saved.getMethod().name(),
                "amount", saved.getAmount(),
                "details", Map.of(
                        "strategyName", getClass().getSimpleName(),
                        "reason", request.getReason() == null ? "" : request.getReason(),
                        "refundAmount", refundAmount,
                        "refundSurgeIncluded", surgeIncluded
                )
        ));
        ctx.cache.invalidateAllPaymentFeatureCaches(saved.getId());

        return saved;
    }

    protected abstract double computeRefundAmount(Payment payment);

    protected abstract boolean isSurgeIncluded();
}
