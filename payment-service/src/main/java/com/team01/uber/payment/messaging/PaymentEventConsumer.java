package com.team01.uber.payment.messaging;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import com.team01.uber.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = PaymentEventConfig.SAGA_LISTENER_QUEUE)
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentService paymentService;

    public PaymentEventConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @RabbitHandler
    public void onRideCompleted(RideCompletedEvent event,
                                @Header(value = "correlationId", required = false) String correlationId) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_RIDE_COMPLETED);
        MDC.put("correlationId", correlationId != null ? correlationId : "");
        try {
            paymentService.processRideCompleted(event);
        } catch (Exception e) {
            log.error("Failed to process ride.completed: {}", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
        }
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event,
                                @Header(value = "correlationId", required = false) String correlationId) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_RIDE_CANCELLED);
        MDC.put("correlationId", correlationId != null ? correlationId : "");
        try {
            paymentService.processRideCancelled(event);
        } catch (Exception e) {
            log.error("Failed to process ride.cancelled: {}", e.getMessage());
            throw new RuntimeException(e);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
        }
    }
}
