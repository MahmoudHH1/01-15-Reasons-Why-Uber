package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;

public abstract class RefundResult {

    private final double amount;
    private final String reasonCode;

    protected RefundResult(double amount, String reasonCode) {
        this.amount = amount;
        this.reasonCode = reasonCode;
    }

    public double getAmount() {
        return amount;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public abstract Payment apply(Payment payment, RefundSurgeRequest request, RefundContext ctx, String strategyName);
}
