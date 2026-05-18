package com.team01.uber.driver.messaging;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.driver.config.DriverEventConfig;
import com.team01.uber.driver.service.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = DriverEventConfig.RIDE_SAGA_QUEUE)
public class RideEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideEventConsumer.class);

    private final DriverService driverService;
    private final SagaFeedbackPublisher feedbackPublisher;

    public RideEventConsumer(DriverService driverService, SagaFeedbackPublisher feedbackPublisher) {
        this.driverService = driverService;
        this.feedbackPublisher = feedbackPublisher;
    }

    @RabbitHandler
    public void onRidePlaced(RidePlacedEvent event, Message message) {
        withMdc(message, DriverEventConfig.ROUTING_RIDE_PLACED, event.driverId(), event.rideId(), () -> {
            log.info("Consuming {} for rideId={}", DriverEventConfig.ROUTING_RIDE_PLACED, event.rideId());
            driverService.handleRidePlaced(event.driverId(), event.rideId());
        });
    }

    @RabbitHandler
    public void onRideCompleted(RideCompletedEvent event, Message message) {
        String correlationId = extractCorrelationId(message);
        withMdc(message, DriverEventConfig.ROUTING_RIDE_COMPLETED, event.driverId(), event.rideId(), () -> {
            log.info("Consuming {} for rideId={}", DriverEventConfig.ROUTING_RIDE_COMPLETED, event.rideId());
            try {
                driverService.handleRideCompleted(event.driverId(), event.rideId(), event.fare());
            } catch (RuntimeException terminal) {
                log.error("Terminal failure on ride.completed rideId={}: {}", event.rideId(),
                        terminal.getMessage(), terminal);
                feedbackPublisher.publish(
                        event.rideId(), "ride.completed", "completed",
                        classifyReason(terminal), terminal.getMessage(), correlationId);
                throw new AmqpRejectAndDontRequeueException("NACK published to ride.saga-feedback", terminal);
            }
        });
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event, Message message) {
        String correlationId = extractCorrelationId(message);
        withMdc(message, DriverEventConfig.ROUTING_RIDE_CANCELLED, event.driverId(), event.rideId(), () -> {
            log.info("Consuming {} for rideId={}", DriverEventConfig.ROUTING_RIDE_CANCELLED, event.rideId());
            try {
                driverService.handleRideCancelled(event.driverId(), event.rideId());
            } catch (RuntimeException terminal) {
                log.error("Terminal failure on ride.cancelled rideId={} - anti-recursion path: {}",
                        event.rideId(), terminal.getMessage(), terminal);
                feedbackPublisher.publish(
                        event.rideId(), "ride.cancelled", "cancelled",
                        classifyReason(terminal), terminal.getMessage(), correlationId);
                throw new AmqpRejectAndDontRequeueException("NACK published to ride.saga-feedback (compensation failed)", terminal);
            }
        });
    }

    @RabbitHandler(isDefault = true)
    public void onUnknown(Object payload, Message message) {
        log.warn("Unhandled message on {} with routingKey={} payloadType={}",
                DriverEventConfig.RIDE_SAGA_QUEUE,
                message.getMessageProperties().getReceivedRoutingKey(),
                payload == null ? "null" : payload.getClass().getName());
    }

    private String classifyReason(Throwable t) {
        String name = t.getClass().getSimpleName().toLowerCase();
        if (name.contains("optimisticlock")) return "driver_lock_conflict";
        if (name.contains("notfound")) return "driver_not_found";
        return "driver_handler_failure";
    }

    private void withMdc(Message message, String routingKey, Long driverId, Long rideId, Runnable body) {
        String correlationId = extractCorrelationId(message);
        try {
            if (correlationId != null) MDC.put("correlationId", correlationId);
            MDC.put("routingKey", routingKey);
            if (driverId != null) MDC.put("driverId", driverId.toString());
            if (rideId != null) MDC.put("rideId", rideId.toString());
            body.run();
        } finally {
            MDC.clear();
        }
    }

    private String extractCorrelationId(Message message) {
        Object header = message.getMessageProperties().getHeader("X-Correlation-ID");
        if (header == null) header = message.getMessageProperties().getHeader("correlationId");
        return header == null ? null : header.toString();
    }
}
