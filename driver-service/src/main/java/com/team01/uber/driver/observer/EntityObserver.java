package com.team01.uber.driver.observer;

public interface EntityObserver {
    void onEvent(String eventType, Object payload);
}
