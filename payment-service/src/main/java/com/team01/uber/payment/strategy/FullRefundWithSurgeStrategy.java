package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;

public class FullRefundWithSurgeStrategy implements RefundStrategy {

    @Override
    public RefundResult calculateRefund(Payment payment, RefundSurgeRequest request) {
        return new ApprovedRefundResult(payment.getAmount(), "full_refund_with_surge", true);
    }
}
