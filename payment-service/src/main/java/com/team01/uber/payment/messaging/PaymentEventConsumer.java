package com.team01.uber.payment.messaging;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import com.team01.uber.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = PaymentEventConfig.SAGA_LISTENER_QUEUE)
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentService paymentService;
    private final SagaFeedbackPublisher feedbackPublisher;

    public PaymentEventConsumer(PaymentService paymentService, SagaFeedbackPublisher feedbackPublisher) {
        this.paymentService = paymentService;
        this.feedbackPublisher = feedbackPublisher;
    }

    @RabbitHandler
    public void onRideCompleted(RideCompletedEvent event,
                                @Header(value = "correlationId", required = false) String correlationId) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_RIDE_COMPLETED);
        MDC.put("correlationId", correlationId != null ? correlationId : "");
        MDC.put("rideId", String.valueOf(event.rideId()));
        MDC.put("userId", String.valueOf(event.userId()));
        try {
            paymentService.processRideCompleted(event);
        } catch (RuntimeException terminal) {
            log.error("Terminal failure on ride.completed rideId={}: {}",
                    event.rideId(), terminal.getMessage(), terminal);
            feedbackPublisher.publish(
                    event.rideId(), "ride.completed", "completed",
                    classifyReason(terminal), terminal.getMessage(), correlationId);
            throw new AmqpRejectAndDontRequeueException("NACK published to ride.saga-feedback", terminal);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
            MDC.remove("rideId");
            MDC.remove("userId");
        }
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event,
                                @Header(value = "correlationId", required = false) String correlationId) {
        MDC.put("routingKey", PaymentEventConfig.ROUTING_RIDE_CANCELLED);
        MDC.put("correlationId", correlationId != null ? correlationId : "");
        MDC.put("rideId", String.valueOf(event.rideId()));
        MDC.put("userId", String.valueOf(event.userId()));
        try {
            paymentService.processRideCancelled(event);
        } catch (RuntimeException terminal) {
            log.error("Terminal failure on ride.cancelled rideId={} - anti-recursion path: {}",
                    event.rideId(), terminal.getMessage(), terminal);
            feedbackPublisher.publish(
                    event.rideId(), "ride.cancelled", "cancelled",
                    classifyReason(terminal), terminal.getMessage(), correlationId);
            throw new AmqpRejectAndDontRequeueException("NACK published to ride.saga-feedback (compensation failed)", terminal);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
            MDC.remove("rideId");
            MDC.remove("userId");
        }
    }

    private String classifyReason(Throwable t) {
        String name = t.getClass().getSimpleName().toLowerCase();
        if (name.contains("optimisticlock")) return "payment_lock_conflict";
        if (name.contains("dataintegrity") || name.contains("constraintviolation")) return "payment_db_conflict";
        if (name.contains("notfound")) return "payment_target_not_found";
        return "payment_handler_failure";
    }
}
