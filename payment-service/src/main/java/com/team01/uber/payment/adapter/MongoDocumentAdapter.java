package com.team01.uber.payment.adapter;

import com.team01.uber.payment.dto.PaymentMethodDTO;
import org.bson.Document;
import org.springframework.stereotype.Component;

@Component
public class MongoDocumentAdapter {

    public PaymentMethodDTO adapt(Document source) {
        String method = source.getString("_id");

        long successCount = source.get("successCount") != null
                ? ((Number) source.get("successCount")).longValue() : 0L;
        long failureCount = source.get("failureCount") != null
                ? ((Number) source.get("failureCount")).longValue() : 0L;
        double totalAmount = source.get("totalAmount") != null
                ? ((Number) source.get("totalAmount")).doubleValue() : 0.0;

        long denominator = successCount + failureCount;
        double successRate = denominator > 0 ? (double) successCount / denominator : 0.0;

        return PaymentMethodDTO.builder()
                .method(method)
                .successCount(successCount)
                .failureCount(failureCount)
                .successRate(successRate)
                .totalAmount(totalAmount)
                .build();
    }
}
