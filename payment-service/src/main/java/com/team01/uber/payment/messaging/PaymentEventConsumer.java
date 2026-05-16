package com.team01.uber.payment.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import com.team01.uber.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    public PaymentEventConsumer(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = PaymentEventConfig.SAGA_LISTENER_QUEUE)
    public void handle(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody());
        Object correlationIdHeader = message.getMessageProperties().getHeaders().get("correlationId");
        String correlationId = correlationIdHeader != null ? correlationIdHeader.toString() : "";
        MDC.put("correlationId", correlationId);
        MDC.put("routingKey", routingKey);
        try {
            if (PaymentEventConfig.ROUTING_RIDE_COMPLETED.equals(routingKey)) {
                RideCompletedEvent event = objectMapper.readValue(body, RideCompletedEvent.class);
                paymentService.processRideCompleted(event);
            } else if (PaymentEventConfig.ROUTING_RIDE_CANCELLED.equals(routingKey)) {
                RideCancelledEvent event = objectMapper.readValue(body, RideCancelledEvent.class);
                paymentService.processRideCancelled(event);
            } else {
                log.warn("Unrecognised routing key on payment.saga-listener: {}", routingKey);
            }
        } catch (Exception e) {
            log.error("Failed to process {}: {}", routingKey, e.getMessage());
            throw new RuntimeException(e);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("routingKey");
        }
    }
}
