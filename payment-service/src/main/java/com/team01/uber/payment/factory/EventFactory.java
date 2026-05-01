package com.team01.uber.payment.factory;

import com.team01.uber.payment.enums.EventType;
import com.team01.uber.payment.model.MongoEvent;
import com.team01.uber.payment.model.PaymentAuditEvent;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class EventFactory {

    public static MongoEvent createEvent(EventType type, Map<String, Object> params) {
        return switch (type) {
            case PAYMENT_AUDIT -> buildPaymentAuditEvent(params);
            default -> throw new UnsupportedOperationException(
                    "Unsupported event type in payment-service: " + type);
        };
    }

    private static PaymentAuditEvent buildPaymentAuditEvent(Map<String, Object> params) {
        PaymentAuditEvent event = new PaymentAuditEvent();

        if (params.get("paymentId") != null) {
            event.setPaymentId(((Number) params.get("paymentId")).longValue());
        }
        event.setAction((String) params.get("action"));

        Object ts = params.get("timestamp");
        event.setTimestamp(ts instanceof LocalDateTime ldt ? ldt : LocalDateTime.now());

        if (params.get("method") != null) {
            event.setMethod(params.get("method").toString());
        }
        if (params.get("amount") != null) {
            event.setAmount(((Number) params.get("amount")).doubleValue());
        }

        Map<String, Object> details = new HashMap<>(params);
        details.remove("paymentId");
        details.remove("action");
        details.remove("timestamp");
        details.remove("method");
        details.remove("amount");
        event.setDetails(details.isEmpty() ? null : details);

        return event;
    }
}
