package com.team01.uber.driver.factory;

import com.team01.uber.driver.enums.EventType;
import com.team01.uber.driver.model.DriverEvent;
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
            case DRIVER -> new DriverEvent(
                    (Long) params.get("driverId"), action, timestamp, details);
            default -> throw new UnsupportedOperationException(
                    "Event type " + type + " is not handled by driver-service");
        };
    }
}
