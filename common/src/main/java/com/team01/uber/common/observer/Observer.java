package com.team01.uber.common.observer;

import java.util.Map;

public interface Observer {
    void onEvent(String action, Map<String, Object> payload);
}