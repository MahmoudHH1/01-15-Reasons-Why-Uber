package com.team01.uber.payment.strategy;

import com.team01.uber.payment.dto.RefundSurgeRequest;
import com.team01.uber.payment.model.Payment;

import java.util.Map;

public class BaseFareOnlyRefundStrategy implements RefundStrategy {

    @Override
    public RefundResult calculateRefund(Payment payment, RefundSurgeRequest request) {
        double surgeFee = extractSurgeFee(payment);
        double refundAmount = payment.getAmount() - surgeFee;
        return new RefundResult(refundAmount, "base_fare_only");
    }

    private double extractSurgeFee(Payment payment) {
        Map<String, Object> details = payment.getTransactionDetails();
        if (details != null && details.containsKey("surgeFee") && details.get("surgeFee") != null) {
            return ((Number) details.get("surgeFee")).doubleValue();
        }
        // fallback: 15% of amount for pre-M2 payments without the key
        return payment.getAmount() * 0.15;
    }
}
