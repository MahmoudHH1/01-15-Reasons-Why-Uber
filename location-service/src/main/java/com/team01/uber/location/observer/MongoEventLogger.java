package com.team01.uber.location.observer;

import com.team01.uber.location.enums.EventType;
import com.team01.uber.location.factory.EventFactory;
import com.team01.uber.location.model.LocationEvent;
import com.team01.uber.location.model.MongoEvent;
import com.team01.uber.location.repository.LocationEventRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MongoEventLogger implements EntityObserver {

    private final LocationEventRepository locationEventRepository;
    private final EventFactory eventFactory;

    public MongoEventLogger(LocationEventRepository locationEventRepository, EventFactory eventFactory) {
        this.locationEventRepository = locationEventRepository;
        this.eventFactory = eventFactory;
    }

    @Override
    public void onEvent(String action, Object payload) {
        if (!(payload instanceof Map<?, ?> raw)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) raw;

        MongoEvent event = eventFactory.createEvent(EventType.LOCATION,
                Map.of("driverId", params.get("driverId"),
                       "action", action,
                       "details", params));

        if (event instanceof LocationEvent le) {
            locationEventRepository.save(le);
        }
    }
}
