package com.team01.uber.payment.strategy;

import com.team01.uber.payment.repository.PaymentRepository;
import com.team01.uber.payment.service.CacheInvalidationService;

public class RefundContext {

    public final PaymentRepository repository;
    public final RefundEventNotifier notifier;
    public final CacheInvalidationService cache;

    public RefundContext(PaymentRepository repository,
                         RefundEventNotifier notifier,
                         CacheInvalidationService cache) {
        this.repository = repository;
        this.notifier = notifier;
        this.cache = cache;
    }

    @FunctionalInterface
    public interface RefundEventNotifier {
        void notify(String eventType, Object payload);
    }
}
