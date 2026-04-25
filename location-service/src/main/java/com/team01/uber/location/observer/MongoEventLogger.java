package com.team01.uber.location.observer;

import com.team01.uber.location.model.LocationEvent;
import com.team01.uber.location.repository.LocationEventRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class MongoEventLogger implements EntityObserver {

    private final LocationEventRepository locationEventRepository;

    public MongoEventLogger(LocationEventRepository locationEventRepository) {
        this.locationEventRepository = locationEventRepository;
    }

    @Override
    public void onEvent(String action, Object payload) {
        if (!(payload instanceof Map<?, ?> raw)) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) raw;

        MongoEvent event = EventFactory.createEvent(EventType.LOCATION,
                Map.of("driverId", params.get("driverId"),
                       "action", action,
                       "latitude", params.getOrDefault("latitude", null),
                       "longitude", params.getOrDefault("longitude", null)));

        if (event instanceof LocationEvent le) {
            locationEventRepository.save(le);
        }
    }
}
