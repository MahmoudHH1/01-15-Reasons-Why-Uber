package com.team01.uber.payment.observer;

import com.team01.uber.payment.enums.EventType;
import com.team01.uber.payment.factory.EventFactory;
import com.team01.uber.payment.model.PaymentAuditEvent;
import com.team01.uber.payment.repository.PaymentAuditEventRepository;
import com.team01.uber.payment.service.CacheInvalidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MongoEventLogger implements EntityObserver {

    private static final Logger log = LoggerFactory.getLogger(MongoEventLogger.class);

    private final PaymentAuditEventRepository repository;
    private final CacheInvalidationService cacheInvalidationService;

    public MongoEventLogger(PaymentAuditEventRepository repository,
                             CacheInvalidationService cacheInvalidationService) {
        this.repository = repository;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    @Async
    @Override
    public void onEvent(String eventType, Object payload) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("action", eventType);
            if (payload instanceof Map<?, ?> payloadMap) {
                payloadMap.forEach((k, v) -> params.put(k.toString(), v));
            }
            PaymentAuditEvent event = (PaymentAuditEvent)
                    EventFactory.createEvent(EventType.PAYMENT_AUDIT, params);
            repository.save(event);

            if (!"ANALYTICS_VIEWED".equals(eventType) && !"DASHBOARD_VIEWED".equals(eventType)) {
                cacheInvalidationService.invalidateAnalyticsCaches();
            }
        } catch (Exception e) {
            log.warn("MongoDB event logging failed for event {}: {}", eventType, e.getMessage());
        }
    }
}
