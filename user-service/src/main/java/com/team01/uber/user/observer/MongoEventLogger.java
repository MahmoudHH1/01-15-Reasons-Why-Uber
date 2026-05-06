package com.team01.uber.user.observer;

import com.team01.uber.user.mongo.EventType;
import com.team01.uber.user.factory.EventFactory;
import com.team01.uber.user.model.mongo.AuthEvent;
import com.team01.uber.user.repository.AuthEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class MongoEventLogger implements EntityObserver {

    private final EventType boundEventType = EventType.AUTH;
    private final AuthEventRepository authEventRepository;

    public MongoEventLogger(AuthEventRepository authEventRepository) {
        this.authEventRepository = authEventRepository;
    }

    @Override
    public void onEvent(String action, Object payload) {
        try {
            Map<String, Object> params = new HashMap<>((Map<String, Object>) payload);
            params.put("action", action);
            AuthEvent event = (AuthEvent) EventFactory.createEvent(boundEventType, params);
            authEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to persist auth event '{}' to MongoDB: {}", action, e.getMessage());
        }
    }
}