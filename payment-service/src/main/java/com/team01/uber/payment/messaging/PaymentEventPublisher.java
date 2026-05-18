package com.team01.uber.payment.messaging;

import com.team01.uber.contracts.events.PaymentCompletedEvent;
import com.team01.uber.contracts.events.PaymentFailedEvent;
import com.team01.uber.contracts.events.PaymentInitiatedEvent;
import com.team01.uber.contracts.events.PaymentRefundedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public PaymentEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    private MessagePostProcessor correlationIdPostProcessor() {
        String correlationId = MDC.get("correlationId");
        return msg -> {
            if (correlationId != null) {
                msg.getMessageProperties().getHeaders().put("correlationId", correlationId);
            }
            return msg;
        };
    }

    private void setPaymentEventMdc(String routingKey, Long paymentId, Long rideId) {
        MDC.put("routingKey", routingKey);
        if (paymentId != null) MDC.put("paymentId", String.valueOf(paymentId));
        if (rideId != null)    MDC.put("rideId",    String.valueOf(rideId));
    }

    private void clearPaymentEventMdc() {
        MDC.remove("routingKey");
        MDC.remove("paymentId");
        MDC.remove("rideId");
    }

    public void publishInitiated(PaymentInitiatedEvent event) {
        setPaymentEventMdc(PaymentEventConfig.ROUTING_PAYMENT_INITIATED, event.paymentId(), event.rideId());
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_INITIATED,
                    event,
                    correlationIdPostProcessor());
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_INITIATED, event.paymentId());
        } finally {
            clearPaymentEventMdc();
        }
    }

    public void publishCompleted(PaymentCompletedEvent event) {
        setPaymentEventMdc(PaymentEventConfig.ROUTING_PAYMENT_COMPLETED, event.paymentId(), event.rideId());
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_COMPLETED,
                    event,
                    correlationIdPostProcessor());
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_COMPLETED, event.paymentId());
        } finally {
            clearPaymentEventMdc();
        }
    }

    public void publishFailed(PaymentFailedEvent event) {
        setPaymentEventMdc(PaymentEventConfig.ROUTING_PAYMENT_FAILED, event.paymentId(), event.rideId());
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_FAILED,
                    event,
                    correlationIdPostProcessor());
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_FAILED, event.paymentId());
        } finally {
            clearPaymentEventMdc();
        }
    }

    public void publishRefunded(PaymentRefundedEvent event) {
        setPaymentEventMdc(PaymentEventConfig.ROUTING_PAYMENT_REFUNDED, event.paymentId(), event.rideId());
        try {
            rabbitTemplate.convertAndSend(
                    PaymentEventConfig.PAYMENT_EVENTS_EXCHANGE,
                    PaymentEventConfig.ROUTING_PAYMENT_REFUNDED,
                    event,
                    correlationIdPostProcessor());
            log.info("Published {} for paymentId={}", PaymentEventConfig.ROUTING_PAYMENT_REFUNDED, event.paymentId());
        } finally {
            clearPaymentEventMdc();
        }
    }
}
