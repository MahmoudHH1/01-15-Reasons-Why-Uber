package com.team01.uber.payment.messaging;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import com.team01.uber.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
    public void onRideCompleted(RideCompletedEvent event) {
        try {
            paymentService.processRideCompleted(event);
        } catch (Exception e) {
            log.error("Failed to process ride.completed: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event) {
        try {
            paymentService.processRideCancelled(event);
        } catch (Exception e) {
            log.error("Failed to process ride.cancelled: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
