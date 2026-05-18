package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

public class DeniedRefundResult extends RefundResult {

    public DeniedRefundResult(double amount, String reasonCode) {
        super(amount, reasonCode);
    }

    @Override
    public Payment apply(Payment payment, RefundSurgeRequest request, RefundContext ctx, String strategyName) {
        ctx.notifier.notify("REFUND_DENIED", Map.of(
                "paymentId", payment.getId(),
                "method", payment.getMethod() != null ? payment.getMethod().name() : "UNKNOWN",
                "amount", payment.getAmount(),
                "details", Map.of(
                        "strategyName", strategyName,
                        "denialReason", getReasonCode()
                )
        ));
        ctx.cache.invalidatePaymentCaches(payment.getId());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, getReasonCode());
    }
}
