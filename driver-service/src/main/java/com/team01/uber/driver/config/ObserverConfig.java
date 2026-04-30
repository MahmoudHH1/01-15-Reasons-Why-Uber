package com.team01.uber.driver.config;

import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.service.DriverService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserverConfig {

    private final DriverService driverService;
    private final MongoEventLogger mongoEventLogger;

    public ObserverConfig(DriverService driverService, MongoEventLogger mongoEventLogger) {
        this.driverService = driverService;
        this.mongoEventLogger = mongoEventLogger;
    }

    @PostConstruct
    public void registerObservers() {
        driverService.register(mongoEventLogger);
    }
}
