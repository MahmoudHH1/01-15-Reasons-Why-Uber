package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class RefundStrategySelector {

    public RefundStrategy select(Payment payment, RefundSurgeRequest request) {
        boolean withinWindow = payment.getCreatedAt().isAfter(LocalDateTime.now().minusHours(24));
        if (!withinWindow) {
            return new NoRefundStrategy();
        }
        if (request.isRefundSurge()) {
            return new FullRefundWithSurgeStrategy();
        }
        return new BaseFareOnlyRefundStrategy();
    }
}
