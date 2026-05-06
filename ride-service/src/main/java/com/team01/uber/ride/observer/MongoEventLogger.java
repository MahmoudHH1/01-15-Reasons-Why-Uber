package com.team01.uber.ride.observer;

import com.team01.uber.ride.enums.EventType;
import com.team01.uber.ride.factory.EventFactory;
import com.team01.uber.ride.model.MongoEvent;
import com.team01.uber.ride.model.RideEvent;
import com.team01.uber.ride.repository.RideEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class MongoEventLogger implements EntityObserver {

    private final EventFactory eventFactory;
    private final RideEventRepository rideEventRepository;
    private final EventType boundEventType;

    public MongoEventLogger(EventFactory eventFactory, RideEventRepository rideEventRepository) {
        this.eventFactory = eventFactory;
        this.rideEventRepository = rideEventRepository;
        this.boundEventType = EventType.RIDE;
    }

    @Override
    public void onEvent(String action, Object payload) {
        try {
            Map<String, Object> payloadMap = (Map<String, Object>) payload;

            Map<String, Object> params = new HashMap<>();
            params.put("action", action);
            params.put("rideId", payloadMap.get("rideId"));
            params.put("details", payloadMap);

            MongoEvent event = eventFactory.createEvent(boundEventType, params);
            if (event instanceof RideEvent rideEvent) {
                rideEventRepository.save(rideEvent);
            }
        } catch (Exception e) {
            log.warn("Failed to persist ride event '{}' to MongoDB: {}", action, e.getMessage());
        }
    }
}