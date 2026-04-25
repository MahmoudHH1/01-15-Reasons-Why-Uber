package com.team01.uber.location.config;

import com.team01.uber.location.observer.MongoEventLogger;
import com.team01.uber.location.service.LocationService;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class ObserverConfig {

    private final LocationService locationService;
    private final MongoEventLogger mongoEventLogger;

    public ObserverConfig(LocationService locationService, MongoEventLogger mongoEventLogger) {
        this.locationService = locationService;
        this.mongoEventLogger = mongoEventLogger;
    }

    @PostConstruct
    public void registerObservers() {
        locationService.register(mongoEventLogger);
    }
}
