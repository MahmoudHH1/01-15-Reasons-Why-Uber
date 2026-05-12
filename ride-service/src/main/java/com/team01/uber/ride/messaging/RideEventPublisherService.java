package com.team01.uber.ride.messaging;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.ride.model.Ride;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RideEventPublisherService {

    private static final String RIDE_EXCHANGE = "ride.events";

    private final RabbitTemplate rabbitTemplate;

    public RideEventPublisherService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishRidePlaced(Ride ride) {
        rabbitTemplate.convertAndSend(RIDE_EXCHANGE, "ride.placed",
                new RidePlacedEvent(ride.getId(), ride.getUserId(), ride.getDriverId()));
    }

    public void publishRideCompleted(Ride ride) {
        rabbitTemplate.convertAndSend(RIDE_EXCHANGE, "ride.completed",
                new RideCompletedEvent(ride.getId(), ride.getUserId(), ride.getDriverId(), ride.getFare()));
    }

    public void publishRideCancelled(Ride ride, String reason) {
        rabbitTemplate.convertAndSend(RIDE_EXCHANGE, "ride.cancelled",
                new RideCancelledEvent(ride.getId(), ride.getUserId(), ride.getDriverId(), reason));
    }
}
