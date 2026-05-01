package com.team01.uber.ride.observer;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RideEventPublisher implements Observable {

    private final List<EntityObserver> observers;

    public RideEventPublisher(List<EntityObserver> observers) {
        this.observers = new CopyOnWriteArrayList<>(observers);
    }

    @Override
    public void registerObserver(EntityObserver observer) {
        observers.add(observer);
    }

    @Override
    public void unregisterObserver(EntityObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers(String action, Map<String, Object> payload) {
        for (EntityObserver observer : observers) {
            observer.onEvent(action, payload);
        }
    }
}