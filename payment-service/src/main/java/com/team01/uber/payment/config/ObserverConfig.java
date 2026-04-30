package com.team01.uber.payment.config;

import com.team01.uber.payment.observer.MongoEventLogger;
import com.team01.uber.payment.service.PaymentService;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class ObserverConfig {

    private final PaymentService paymentService;
    private final MongoEventLogger mongoEventLogger;

    public ObserverConfig(PaymentService paymentService, MongoEventLogger mongoEventLogger) {
        this.paymentService = paymentService;
        this.mongoEventLogger = mongoEventLogger;
    }

    @PostConstruct
    public void registerObservers() {
        paymentService.register(mongoEventLogger);
    }
}
