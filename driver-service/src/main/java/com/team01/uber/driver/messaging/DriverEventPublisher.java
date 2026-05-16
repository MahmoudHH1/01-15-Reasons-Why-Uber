package com.team01.uber.driver.messaging;

import com.team01.uber.contracts.events.DriverDocumentVerifiedEvent;
import com.team01.uber.contracts.events.DriverRatedEvent;
import com.team01.uber.contracts.events.DriverStatusChangedEvent;
import com.team01.uber.driver.config.DriverEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class DriverEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DriverEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public DriverEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishStatusChanged(Long driverId, String oldStatus, String newStatus) {
        DriverStatusChangedEvent payload = new DriverStatusChangedEvent(driverId, oldStatus, newStatus);
        publishAfterCommit(
                DriverEventConfig.DRIVER_EVENTS_EXCHANGE,
                DriverEventConfig.ROUTING_DRIVER_STATUS_CHANGED,
                payload,
                "driverId",
                driverId);
    }

    public void publishRated(Long driverId, Long rideId, Double rating, Long userId) {
        DriverRatedEvent payload = new DriverRatedEvent(driverId, rideId, rating, userId);
        publishAfterCommit(
                DriverEventConfig.DRIVER_EVENTS_EXCHANGE,
                DriverEventConfig.ROUTING_DRIVER_RATED,
                payload,
                "driverId",
                driverId);
    }

    public void publishDocumentVerified(Long driverId, Long documentId, Long verifiedBy) {
        DriverDocumentVerifiedEvent payload = new DriverDocumentVerifiedEvent(driverId, documentId, verifiedBy);
        publishAfterCommit(
                DriverEventConfig.DRIVER_EVENTS_EXCHANGE,
                DriverEventConfig.ROUTING_DRIVER_DOCUMENT_VERIFIED,
                payload,
                "documentId",
                documentId);
    }

    private void publishAfterCommit(String exchange,
                                    String routingKey,
                                    Object payload,
                                    String entityName,
                                    Object entityId) {
        String correlationId = MDC.get("correlationId");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doPublish(exchange, routingKey, payload, entityName, entityId, correlationId);
                }
            });
        } else {
            doPublish(exchange, routingKey, payload, entityName, entityId, correlationId);
        }
    }

    private void doPublish(String exchange,
                           String routingKey,
                           Object payload,
                           String entityName,
                           Object entityId,
                           String correlationId) {
        String priorRoutingKey = MDC.get("routingKey");
        try {
            MDC.put("routingKey", routingKey);
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
                if (correlationId != null && !correlationId.isBlank()) {
                    message.getMessageProperties().setHeader("X-Correlation-ID", correlationId);
                    message.getMessageProperties().setHeader("correlationId", correlationId);
                }
                return message;
            });
            log.info("Published {} for {}={}", routingKey, entityName, entityId);
        } catch (AmqpException e) {
            log.error("Failed to publish {}: {}", routingKey, e.getMessage(), e);
        } finally {
            if (priorRoutingKey != null) {
                MDC.put("routingKey", priorRoutingKey);
            } else {
                MDC.remove("routingKey");
            }
        }
    }
}
