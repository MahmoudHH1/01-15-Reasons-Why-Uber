package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;

public class NoRefundStrategy implements RefundStrategy {

    @Override
    public RefundResult calculateRefund(Payment payment, RefundSurgeRequest request) {
        return new DeniedRefundResult(0, "refund_window_expired");
    }
}
