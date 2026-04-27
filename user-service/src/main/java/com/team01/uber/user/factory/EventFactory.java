package com.team01.uber.user.factory;

import com.team01.uber.user.mongo.EventType;
import com.team01.uber.user.mongo.MongoEvent;
import com.team01.uber.user.model.mongo.AuthEvent;

import java.time.LocalDateTime;
import java.util.Map;

public class EventFactory {

    public static MongoEvent createEvent(EventType type, Map<String, Object> params) {
        return switch (type) {
            case AUTH -> new AuthEvent(
                    params.get("userId") != null ? ((Number) params.get("userId")).longValue() : null,
                    (String) params.get("action"),
                    LocalDateTime.now(),
                    params
            );
            default -> throw new IllegalArgumentException("Unsupported event type: " + type);
        };
    }
}