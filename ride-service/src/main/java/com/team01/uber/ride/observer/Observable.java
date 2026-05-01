package com.team01.uber.ride.observer;

import java.util.Map;

public interface Observable {
    void registerObserver(EntityObserver observer);
    void unregisterObserver(EntityObserver observer);
    void notifyObservers(String action, Map<String, Object> payload);
}