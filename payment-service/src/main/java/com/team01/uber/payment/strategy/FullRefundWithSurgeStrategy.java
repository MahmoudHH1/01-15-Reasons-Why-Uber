package com.team01.uber.payment.strategy;

import com.team01.uber.payment.model.Payment;

public class FullRefundWithSurgeStrategy extends ApprovedRefundStrategy {

    @Override
    protected double computeRefundAmount(Payment payment) {
        return payment.getAmount();
    }

    @Override
    protected boolean isSurgeIncluded() {
        return true;
    }
}
