package com.team01.uber.driver.observer;

import com.team01.uber.driver.enums.EventType;
import com.team01.uber.driver.factory.EventFactory;
import com.team01.uber.driver.model.DriverEvent;
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

    private static final EventType BOUND_TYPE = EventType.DRIVER;

    private final DriverEventRepository repository;
    private final EventFactory eventFactory;

    public MongoEventLogger(DriverEventRepository repository, EventFactory eventFactory) {
        this.repository = repository;
        this.eventFactory = eventFactory;
    }

    @Override
    public void onEvent(String action, Object payload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = payload instanceof Map<?, ?>
                    ? (Map<String, Object>) payload
                    : new HashMap<>();

            Map<String, Object> params = new HashMap<>(data);
            params.put("action", action);

            MongoEvent event = eventFactory.createEvent(BOUND_TYPE, params);
            if (event instanceof DriverEvent driverEvent) {
                repository.save(driverEvent);
            }
        } catch (Exception e) {
            log.warn("Failed to write {} event to MongoDB: {}", action, e.getMessage());
        }
    }
}
