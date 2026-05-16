package com.team01.uber.driver.messaging;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.driver.config.DriverEventConfig;
import com.team01.uber.driver.service.DriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = DriverEventConfig.RIDE_SAGA_QUEUE)
public class RideEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideEventConsumer.class);

    private final DriverService driverService;

    public RideEventConsumer(DriverService driverService) {
        this.driverService = driverService;
    }

    @RabbitHandler
    public void onRidePlaced(RidePlacedEvent event, Message message) {
        withMdc(message, DriverEventConfig.ROUTING_RIDE_PLACED, event.driverId(), event.rideId(), () -> {
            log.info("Consuming {} for rideId={}", DriverEventConfig.ROUTING_RIDE_PLACED, event.rideId());
            try {
                driverService.handleRidePlaced(event.driverId(), event.rideId());
                log.info("Processed {} for rideId={}", DriverEventConfig.ROUTING_RIDE_PLACED, event.rideId());
            } catch (RuntimeException e) {
                log.error("Failed to process {}: {}", DriverEventConfig.ROUTING_RIDE_PLACED, e.getMessage(), e);
                throw e;
            }
        });
    }

    @RabbitHandler
    public void onRideCompleted(RideCompletedEvent event, Message message) {
        withMdc(message, DriverEventConfig.ROUTING_RIDE_COMPLETED, event.driverId(), event.rideId(), () -> {
            log.info("Consuming {} for rideId={}", DriverEventConfig.ROUTING_RIDE_COMPLETED, event.rideId());
            try {
                driverService.handleRideCompleted(event.driverId(), event.rideId(), event.fare());
                log.info("Processed {} for rideId={}", DriverEventConfig.ROUTING_RIDE_COMPLETED, event.rideId());
            } catch (RuntimeException e) {
                log.error("Failed to process {}: {}", DriverEventConfig.ROUTING_RIDE_COMPLETED, e.getMessage(), e);
                throw e;
            }
        });
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event, Message message) {
        withMdc(message, DriverEventConfig.ROUTING_RIDE_CANCELLED, event.driverId(), event.rideId(), () -> {
            log.info("Consuming {} for rideId={}", DriverEventConfig.ROUTING_RIDE_CANCELLED, event.rideId());
            try {
                driverService.handleRideCancelled(event.driverId(), event.rideId());
                log.info("Processed {} for rideId={}", DriverEventConfig.ROUTING_RIDE_CANCELLED, event.rideId());
            } catch (RuntimeException e) {
                log.error("Failed to process {}: {}", DriverEventConfig.ROUTING_RIDE_CANCELLED, e.getMessage(), e);
                throw e;
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
        if (header == null) {
            header = message.getMessageProperties().getHeader("correlationId");
        }
        return header == null ? null : header.toString();
    }
}
