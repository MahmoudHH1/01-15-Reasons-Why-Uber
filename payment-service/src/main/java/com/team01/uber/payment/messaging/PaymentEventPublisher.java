package com.team01.uber.payment.messaging;

import com.team01.uber.contracts.events.PaymentCompletedEvent;
import com.team01.uber.contracts.events.PaymentFailedEvent;
import com.team01.uber.contracts.events.PaymentInitiatedEvent;
import com.team01.uber.contracts.events.PaymentRefundedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public PaymentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishInitiated(PaymentInitiatedEvent event) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_PAYMENT_INITIATED);
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_INITIATED,
                    event);
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_INITIATED, event.paymentId());
        } finally {
            MDC.remove("routingKey");
        }
    }

    public void publishCompleted(PaymentCompletedEvent event) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_PAYMENT_COMPLETED);
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_COMPLETED,
                    event);
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_COMPLETED, event.paymentId());
        } finally {
            MDC.remove("routingKey");
        }
    }

    public void publishFailed(PaymentFailedEvent event) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_PAYMENT_FAILED);
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_FAILED,
                    event);
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_FAILED, event.paymentId());
        } finally {
            MDC.remove("routingKey");
        }
    }

    public void publishRefunded(PaymentRefundedEvent event) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_PAYMENT_REFUNDED);
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_REFUNDED,
                    event);
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_REFUNDED, event.paymentId());
        } finally {
            MDC.remove("routingKey");
        }
    }
}
