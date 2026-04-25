package com.team01.uber.location.observer;

import com.team01.uber.location.model.LocationEvent;

import java.time.LocalDateTime;
import java.util.Map;

public class EventFactory {

    private EventFactory() {}

    public static MongoEvent createEvent(EventType type, Map<String, Object> params) {
        return switch (type) {
            case LOCATION -> {
                Long driverId = (Long) params.get("driverId");
                String action = (String) params.get("action");
                yield new LocationEvent(driverId, action, LocalDateTime.now(), params);
            }
            default -> throw new IllegalArgumentException("Unsupported event type: " + type);
        };
    }
}
