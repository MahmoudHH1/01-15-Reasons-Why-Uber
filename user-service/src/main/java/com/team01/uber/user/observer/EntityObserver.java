package com.team01.uber.user.observer;

public interface EntityObserver {
    void onEvent(String eventType, Object payload);
}