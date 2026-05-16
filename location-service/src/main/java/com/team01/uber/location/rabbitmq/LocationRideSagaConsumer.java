package com.team01.uber.location.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.location.config.LocationEventConfig;
import com.team01.uber.location.service.LocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class LocationRideSagaConsumer {

    private static final Logger log = LoggerFactory.getLogger(LocationRideSagaConsumer.class);

    private final LocationService locationService;
    private final ObjectMapper objectMapper;

    public LocationRideSagaConsumer(LocationService locationService, ObjectMapper objectMapper) {
        this.locationService = locationService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = LocationEventConfig.RIDE_SAGA_QUEUE)
    public void consume(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String correlationId = (String) message.getMessageProperties().getHeaders().get("X-Correlation-ID");
        if (correlationId != null) MDC.put("correlationId", correlationId);
        MDC.put("routingKey", routingKey);
        try {
            log.info("Consuming {} for location-service", routingKey);
            switch (routingKey) {
                case "ride.placed" -> {
                    RidePlacedEvent event = objectMapper.readValue(message.getBody(), RidePlacedEvent.class);
                    locationService.handleRidePlaced(event);
                }
                case "ride.completed" -> {
                    RideCompletedEvent event = objectMapper.readValue(message.getBody(), RideCompletedEvent.class);
                    locationService.handleRideCompleted(event);
                }
                case "ride.cancelled" -> {
                    RideCancelledEvent event = objectMapper.readValue(message.getBody(), RideCancelledEvent.class);
                    locationService.handleRideCancelled(event);
                }
                default -> log.warn("Unhandled routing key: {}", routingKey);
            }
        } catch (Exception e) {
            log.error("Failed to process {}: {}", routingKey, e.getMessage(), e);
            throw new RuntimeException("Consumer error for " + routingKey, e);
        } finally {
            MDC.remove("routingKey");
            MDC.remove("correlationId");
        }
    }
}
