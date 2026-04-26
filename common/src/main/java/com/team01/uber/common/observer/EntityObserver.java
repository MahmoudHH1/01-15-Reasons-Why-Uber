package com.team01.uber.common.observer;

public interface EntityObserver {
    void onEvent(String eventType, Object payload);
}