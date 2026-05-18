package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;
import com.team01.uber.payment.model.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ApprovedRefundResult extends RefundResult {

    private static final Logger log = LoggerFactory.getLogger(ApprovedRefundResult.class);

    private final boolean surgeIncluded;

    public ApprovedRefundResult(double amount, String reasonCode, boolean surgeIncluded) {
        super(amount, reasonCode);
        this.surgeIncluded = surgeIncluded;
    }

    public boolean isSurgeIncluded() {
        return surgeIncluded;
    }

    @Override
    public Payment apply(Payment payment, RefundSurgeRequest request, RefundContext ctx, String strategyName) {
        payment.setStatus(PaymentStatus.REFUNDED);
        if (payment.getTransactionDetails() == null) {
            payment.setTransactionDetails(new HashMap<>());
        }
        payment.getTransactionDetails().put("refundAmount", getAmount());
        payment.getTransactionDetails().put("refundSurgeIncluded", surgeIncluded);
        payment.getTransactionDetails().put("refundReason", request.getReason());
        payment.getTransactionDetails().put("refundedAt", LocalDateTime.now().toString());

        Payment saved = ctx.repository.save(payment);
        log.info("{} {} saved with status={}", "Payment", saved.getId(), saved.getStatus());

        ctx.notifier.notify("REFUNDED", Map.of(
                "paymentId", saved.getId(),
                "method", saved.getMethod() != null ? saved.getMethod().name() : "UNKNOWN",
                "amount", saved.getAmount(),
                "strategyName", strategyName,
                "reason", request.getReason() == null ? "" : request.getReason(),
                "refundAmount", getAmount(),
                "refundSurgeIncluded", surgeIncluded
        ));
        ctx.cache.invalidateAllPaymentFeatureCaches(saved.getId());

        return saved;
    }
}
