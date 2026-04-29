package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;

public interface RefundStrategy {
    Payment execute(Payment payment, RefundSurgeRequest request, RefundContext ctx);
}
