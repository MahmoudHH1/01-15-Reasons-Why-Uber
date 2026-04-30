package com.team01.uber.driver.adapter;

import com.team01.uber.driver.dto.DriverEventDTO;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class MongoDocumentAdapter {

    public DriverEventDTO adapt(Document document) {
        Long driverId = document.get("driverId") != null
                ? ((Number) document.get("driverId")).longValue()
                : null;

        LocalDateTime timestamp = document.get("timestamp") instanceof LocalDateTime ldt
                ? ldt
                : null;

        @SuppressWarnings("unchecked")
        Map<String, Object> details = document.get("details") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : null;

        return DriverEventDTO.builder()
                .id(document.getString("_id") != null ? document.getString("_id") : document.getString("id"))
                .driverId(driverId)
                .action(document.getString("action"))
                .timestamp(timestamp)
                .details(details)
                .build();
    }
}
