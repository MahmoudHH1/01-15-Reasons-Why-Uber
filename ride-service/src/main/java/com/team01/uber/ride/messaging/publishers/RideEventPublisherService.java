package com.team01.uber.ride.messaging.publishers;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.ride.model.Ride;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RideEventPublisherService {

    private static final Logger log = LoggerFactory.getLogger(RideEventPublisherService.class);
    private static final String RIDE_EXCHANGE = "ride.events";

    private final RabbitTemplate rabbitTemplate;

    public RideEventPublisherService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    private MessagePostProcessor wha() {
        String correlationId = MDC.get("correlationId");
        return msg -> {
            if (correlationId != null) {
                msg.getMessageProperties().getHeaders().put("correlationId", correlationId);
            }
            return msg;
        };
    }

    public void publishRidePlaced(Ride ride) {
        MDC.put("routingKey", "ride.placed");
        MDC.put("rideId", String.valueOf(ride.getId()));
        try {
            rabbitTemplate.convertAndSend(RIDE_EXCHANGE, "ride.placed",
                    new RidePlacedEvent(ride.getId(), ride.getUserId(), ride.getDriverId()),
                    wha());
            log.info("Published ride.placed for rideId={}", ride.getId());
        } finally {
            MDC.remove("routingKey");
            MDC.remove("rideId");
        }
    }

    public void publishRideCompleted(Ride ride) {
        MDC.put("routingKey", "ride.completed");
        MDC.put("rideId", String.valueOf(ride.getId()));
        try {
            rabbitTemplate.convertAndSend(RIDE_EXCHANGE, "ride.completed",
                    new RideCompletedEvent(ride.getId(), ride.getUserId(), ride.getDriverId(), ride.getFare()),
                    wha());
            log.info("Published ride.completed for rideId={}", ride.getId());
        } finally {
            MDC.remove("routingKey");
            MDC.remove("rideId");
        }
    }

    public void publishRideCancelled(Ride ride, String reason) {
        MDC.put("routingKey", "ride.cancelled");
        MDC.put("rideId", String.valueOf(ride.getId()));
        try {
            rabbitTemplate.convertAndSend(RIDE_EXCHANGE, "ride.cancelled",
                    new RideCancelledEvent(ride.getId(), ride.getUserId(), ride.getDriverId(), reason),
                    wha());
            log.info("Published ride.cancelled for rideId={}", ride.getId());
        } finally {
            MDC.remove("routingKey");
            MDC.remove("rideId");
        }
    }
}
