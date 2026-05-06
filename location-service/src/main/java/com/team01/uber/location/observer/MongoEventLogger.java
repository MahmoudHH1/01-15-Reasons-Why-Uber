package com.team01.uber.location.observer;

import com.team01.uber.location.enums.EventType;
import com.team01.uber.location.factory.EventFactory;
import com.team01.uber.location.model.LocationEvent;
import com.team01.uber.location.model.MongoEvent;
import com.team01.uber.location.repository.LocationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MongoEventLogger implements EntityObserver {

    private static final Logger log = LoggerFactory.getLogger(MongoEventLogger.class);

    private final LocationEventRepository locationEventRepository;
    private final EventFactory eventFactory;

    public MongoEventLogger(LocationEventRepository locationEventRepository, EventFactory eventFactory) {
        this.locationEventRepository = locationEventRepository;
        this.eventFactory = eventFactory;
    }

    @Override
    public void onEvent(String action, Object payload) {
        if (!(payload instanceof Map<?, ?> raw)) return;

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) raw;

            Map<String, Object> factoryInput = new HashMap<>();
            factoryInput.put("driverId", params.get("driverId"));
            factoryInput.put("action", action);
            factoryInput.put("details", params);

            MongoEvent event = eventFactory.createEvent(EventType.LOCATION, factoryInput);

            if (event instanceof LocationEvent le) {
                locationEventRepository.save(le);
            }
        } catch (Exception e) {
            log.warn("Failed to persist location event '{}' to MongoDB: {}", action, e.getMessage());
        }
    }
}
