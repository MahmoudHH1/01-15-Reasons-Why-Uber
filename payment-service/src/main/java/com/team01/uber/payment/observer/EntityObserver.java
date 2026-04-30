package com.team01.uber.payment.observer;

public interface EntityObserver {
    void onEvent(String eventType, Object payload);
}
