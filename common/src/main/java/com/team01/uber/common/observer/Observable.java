package com.team01.uber.common.observer;

import java.util.Map;

public interface Observable {
    void registerObserver(Observer observer);
    void unregisterObserver(Observer observer);
    void notifyObservers(String action, Map<String, Object> payload);
}