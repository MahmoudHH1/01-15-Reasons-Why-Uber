package com.team01.uber.location.rabbitmq;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.location.config.LocationEventConfig;
import com.team01.uber.location.service.LocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@RabbitListener(queues = LocationEventConfig.RIDE_SAGA_QUEUE)
public class LocationRideSagaConsumer {

    private static final Logger log = LoggerFactory.getLogger(LocationRideSagaConsumer.class);

    private final LocationService locationService;

    public LocationRideSagaConsumer(LocationService locationService) {
        this.locationService = locationService;
    }

    @RabbitHandler
    public void onRidePlaced(RidePlacedEvent event,
                             @Header(value = "X-Correlation-ID", required = false) String correlationId) {
        MDC.put("routingKey", "ride.placed");
        if (correlationId != null) MDC.put("correlationId", correlationId);
        try {
            log.info("Consuming ride.placed for location-service");
            locationService.handleRidePlaced(event);
        } catch (Exception e) {
            log.error("Failed to process ride.placed: {}", e.getMessage(), e);
            throw new RuntimeException("Consumer error for ride.placed", e);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
        }
    }

    @RabbitHandler
    public void onRideCompleted(RideCompletedEvent event,
                                @Header(value = "X-Correlation-ID", required = false) String correlationId) {
        MDC.put("routingKey", "ride.completed");
        if (correlationId != null) MDC.put("correlationId", correlationId);
        try {
            log.info("Consuming ride.completed for location-service");
            locationService.handleRideCompleted(event);
        } catch (Exception e) {
            log.error("Failed to process ride.completed: {}", e.getMessage(), e);
            throw new RuntimeException("Consumer error for ride.completed", e);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
        }
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event,
                                @Header(value = "X-Correlation-ID", required = false) String correlationId) {
        MDC.put("routingKey", "ride.cancelled");
        if (correlationId != null) MDC.put("correlationId", correlationId);
        try {
            log.info("Consuming ride.cancelled for location-service");
            locationService.handleRideCancelled(event);
        } catch (Exception e) {
            log.error("Failed to process ride.cancelled: {}", e.getMessage(), e);
            throw new RuntimeException("Consumer error for ride.cancelled", e);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
        }
    }
}
