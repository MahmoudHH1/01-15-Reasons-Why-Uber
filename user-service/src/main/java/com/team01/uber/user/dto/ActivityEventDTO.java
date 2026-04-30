package com.team01.uber.user.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class ActivityEventDTO {

    private final String action;
    private final LocalDateTime timestamp;
    private final Map<String, Object> details;

    private ActivityEventDTO(Builder builder) {
        this.action = builder.action;
        this.timestamp = builder.timestamp;
        this.details = builder.details;
    }

    public String getAction() { return action; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public Map<String, Object> getDetails() { return details; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String action;
        private LocalDateTime timestamp;
        private Map<String, Object> details;

        public Builder action(String action) { this.action = action; return this; }
        public Builder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder details(Map<String, Object> details) { this.details = details; return this; }

        public ActivityEventDTO build() {
            return new ActivityEventDTO(this);
        }
    }
}