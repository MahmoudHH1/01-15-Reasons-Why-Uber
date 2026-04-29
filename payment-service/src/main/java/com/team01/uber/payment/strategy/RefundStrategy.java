package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;

public interface RefundStrategy {
    RefundResult calculateRefund(Payment payment, RefundSurgeRequest request);
}
