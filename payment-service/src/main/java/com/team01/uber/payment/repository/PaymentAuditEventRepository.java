package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.PaymentAuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentAuditEventRepository extends MongoRepository<PaymentAuditEvent, String> {
}
