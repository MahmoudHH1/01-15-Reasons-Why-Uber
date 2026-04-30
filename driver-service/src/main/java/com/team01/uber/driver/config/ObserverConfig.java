package com.team01.uber.driver.config;

import com.team01.uber.driver.observer.MongoEventLogger;
import com.team01.uber.driver.service.DriverIndexerService;
import com.team01.uber.driver.service.DriverService;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObserverConfig {

    private final DriverService driverService;
    private final DriverIndexerService driverIndexerService;
    private final MongoEventLogger mongoEventLogger;

    public ObserverConfig(DriverService driverService,
                          DriverIndexerService driverIndexerService,
                          MongoEventLogger mongoEventLogger) {
        this.driverService = driverService;
        this.driverIndexerService = driverIndexerService;
        this.mongoEventLogger = mongoEventLogger;
    }

    @PostConstruct
    public void registerObservers() {
        driverService.register(mongoEventLogger);
        driverIndexerService.register(mongoEventLogger);
    }
}
