package com.team01.uber.payment.strategy;

import com.team01.uber.payment.model.Payment;

import java.util.Map;

public class BaseFareOnlyRefundStrategy extends ApprovedRefundStrategy {

    @Override
    protected double computeRefundAmount(Payment payment) {
        double surgeFee = extractSurgeFee(payment);
        return payment.getAmount() - surgeFee;
    }

    @Override
    protected boolean isSurgeIncluded() {
        return false;
    }

    private double extractSurgeFee(Payment payment) {
        Map<String, Object> details = payment.getTransactionDetails();
        if (details != null && details.containsKey("surgeFee") && details.get("surgeFee") != null) {
            return ((Number) details.get("surgeFee")).doubleValue();
        }
        return payment.getAmount() * 0.15;
    }
}
