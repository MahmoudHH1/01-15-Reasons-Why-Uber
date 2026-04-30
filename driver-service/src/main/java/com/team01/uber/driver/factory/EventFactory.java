package com.team01.uber.driver.factory;

import com.team01.uber.driver.model.DriverEvent;
import com.team01.uber.driver.model.EventType;
import com.team01.uber.driver.model.MongoEvent;
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
            case DRIVER -> {
                Long driverId = params.get("driverId") instanceof Long id ? id
                        : params.get("driverId") instanceof Number n ? n.longValue() : null;
                yield DriverEvent.builder()
                        .driverId(driverId)
                        .action(action)
                        .timestamp(timestamp)
                        .details(details)
                        .build();
            }
            default -> throw new UnsupportedOperationException(
                    "Event type " + type + " is not handled by driver-service");
        };
    }
}
