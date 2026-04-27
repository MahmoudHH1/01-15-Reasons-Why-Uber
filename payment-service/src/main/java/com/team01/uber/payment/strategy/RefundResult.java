package com.team01.uber.payment.strategy;

public class RefundResult {

    private final double amount;
    private final String reasonCode;

    public RefundResult(double amount, String reasonCode) {
        this.amount = amount;
        this.reasonCode = reasonCode;
    }

    public double getAmount() { return amount; }
    public String getReasonCode() { return reasonCode; }
}
