package com.team01.uber.payment.observer;

import com.team01.uber.payment.model.PaymentAuditEvent;
import com.team01.uber.payment.repository.PaymentAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class MongoEventLogger implements EntityObserver {

    private static final Logger log = LoggerFactory.getLogger(MongoEventLogger.class);

    private final PaymentAuditEventRepository auditRepository;

    public MongoEventLogger(PaymentAuditEventRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void onEvent(String eventType, Object payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) payload;

            Long paymentId = (Long) data.get("paymentId");
            String method = (String) data.get("method");
            Double amount = data.get("amount") != null ? ((Number) data.get("amount")).doubleValue() : null;

            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) data.get("details");

            PaymentAuditEvent event = new PaymentAuditEvent(
                    paymentId,
                    eventType,
                    LocalDateTime.now(),
                    method,
                    amount,
                    details
            );
            auditRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to write {} event to MongoDB: {}", eventType, e.getMessage());
        }
    }
}
