package com.team01.uber.driver.observer;

import com.team01.uber.driver.factory.EventFactory;
import com.team01.uber.driver.model.DriverEvent;
import com.team01.uber.driver.model.EventType;
import com.team01.uber.driver.model.MongoEvent;
import com.team01.uber.driver.repository.DriverEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MongoEventLogger implements EntityObserver {

    private static final Logger log = LoggerFactory.getLogger(MongoEventLogger.class);

    private final EventFactory eventFactory;
    private final DriverEventRepository driverEventRepository;

    // driver-service binds DRIVER EventType at construction time
    private final EventType boundEventType = EventType.DRIVER;

    public MongoEventLogger(EventFactory eventFactory, DriverEventRepository driverEventRepository) {
        this.eventFactory = eventFactory;
        this.driverEventRepository = driverEventRepository;
    }

    @Override
    public void onEvent(String eventType, Object payload) {
        log.info("Domain event: {} payload={}", eventType, payload);
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("action", eventType);

            if (payload instanceof Map<?, ?> map) {
                map.forEach((k, v) -> params.put(k.toString(), v));
            }

            MongoEvent event = eventFactory.createEvent(boundEventType, params);
            driverEventRepository.save((DriverEvent) event);
        } catch (Exception e) {
            log.warn("Failed to persist driver event '{}' to MongoDB: {}", eventType, e.getMessage());
        }
    }
}
