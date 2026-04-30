package com.team01.uber.payment.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

@Document(collection = "payment_audit_trail")
public class PaymentAuditEvent {

    @Id
    private String id;

    private Long paymentId;
    private String action;
    private LocalDateTime timestamp;
    private String method;
    private Double amount;
    private Map<String, Object> details;

    public PaymentAuditEvent() {}

    public PaymentAuditEvent(Long paymentId, String action, LocalDateTime timestamp,
                              String method, Double amount, Map<String, Object> details) {
        this.paymentId = paymentId;
        this.action = action;
        this.timestamp = timestamp;
        this.method = method;
        this.amount = amount;
        this.details = details;
    }

    public String getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getAction() { return action; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getMethod() { return method; }
    public Double getAmount() { return amount; }
    public Map<String, Object> getDetails() { return details; }
}
