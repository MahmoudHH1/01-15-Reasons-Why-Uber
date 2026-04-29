package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

public class NoRefundStrategy implements RefundStrategy {

    @Override
    public Payment execute(Payment payment, RefundSurgeRequest request, RefundContext ctx) {
        ctx.notifier.notify("REFUND_DENIED", Map.of(
                "paymentId", payment.getId(),
                "method", payment.getMethod().name(),
                "amount", payment.getAmount(),
                "details", Map.of(
                        "strategyName", "NoRefundStrategy",
                        "denialReason", "refund window expired"
                )
        ));
        ctx.cache.invalidatePaymentCaches(payment.getId());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "refund window expired");
    }
}
