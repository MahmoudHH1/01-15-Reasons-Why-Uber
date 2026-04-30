package com.team01.uber.driver.observer;

public interface EntityObserver {
    void onEvent(String action, Object payload);
}
