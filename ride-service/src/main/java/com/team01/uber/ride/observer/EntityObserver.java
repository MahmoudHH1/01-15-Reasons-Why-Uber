package com.team01.uber.ride.observer;

public interface EntityObserver {
    void onEvent(String action, Object payload);
}