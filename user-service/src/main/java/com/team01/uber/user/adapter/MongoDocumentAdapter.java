package com.team01.uber.user.adapter;

import com.team01.uber.user.dto.ActivityEventDTO;
import com.team01.uber.user.model.mongo.AuthEvent;

public class MongoDocumentAdapter {

    public ActivityEventDTO adapt(AuthEvent authEvent) {
        return ActivityEventDTO.builder()
                .action(authEvent.getAction())
                .timestamp(authEvent.getTimestamp())
                .details(authEvent.getDetails())
                .build();
    }
}