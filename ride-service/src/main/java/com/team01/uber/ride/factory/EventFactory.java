package com.team01.uber.ride.factory;

import com.team01.uber.ride.enums.EventType;
import com.team01.uber.ride.model.MongoEvent;
import com.team01.uber.ride.model.RideEvent;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class EventFactory {

    @SuppressWarnings("unchecked")
    public MongoEvent createEvent(EventType type, Map<String, Object> params) {
        String action = (String) params.get("action");
        LocalDateTime timestamp = params.get("timestamp") instanceof LocalDateTime lt
                ? lt : LocalDateTime.now();
        Map<String, Object> details = params.get("details") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        return switch (type) {
            case RIDE -> RideEvent.builder()
                    .rideId((Long) params.get("rideId"))
                    .action(action)
                    .timestamp(timestamp)
                    .details(details)
                    .build();
            default -> throw new UnsupportedOperationException("Event type " + type + " is not handled by ride-service");
        };
    }
}
