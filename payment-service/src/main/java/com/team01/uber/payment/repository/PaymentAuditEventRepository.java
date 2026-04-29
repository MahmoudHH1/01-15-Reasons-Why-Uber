package com.team01.uber.payment.repository;

import com.team01.uber.payment.model.PaymentAuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentAuditEventRepository extends MongoRepository<PaymentAuditEvent, String> {

    List<PaymentAuditEvent> findByTimestampBetweenAndActionIn(
            LocalDateTime start, LocalDateTime end, List<String> actions);
}
