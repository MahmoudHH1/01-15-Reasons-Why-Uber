package com.team01.uber.location.observer;

public interface EntityObserver {
    void onEvent(String action, Object payload);
}
