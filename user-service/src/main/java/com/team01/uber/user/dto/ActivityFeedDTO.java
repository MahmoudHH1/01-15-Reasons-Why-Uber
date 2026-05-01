package com.team01.uber.user.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;

@JsonDeserialize(builder = ActivityFeedDTO.Builder.class)
public class ActivityFeedDTO {

    private final List<ActivityEventDTO> content;
    private final int page;
    private final int size;
    private final long totalElements;

    private ActivityFeedDTO(Builder builder) {
        this.content = builder.content;
        this.page = builder.page;
        this.size = builder.size;
        this.totalElements = builder.totalElements;
    }

    public List<ActivityEventDTO> getContent() { return content; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }

    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private List<ActivityEventDTO> content;
        private int page;
        private int size;
        private long totalElements;

        public Builder content(List<ActivityEventDTO> content) { this.content = content; return this; }
        public Builder page(int page) { this.page = page; return this; }
        public Builder size(int size) { this.size = size; return this; }
        public Builder totalElements(long totalElements) { this.totalElements = totalElements; return this; }

        public ActivityFeedDTO build() { return new ActivityFeedDTO(this); }
    }
}