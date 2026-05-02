package com.team01.uber.user.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = UserRideSummaryDTO.Builder.class)
public class UserRideSummaryDTO {

    private final Long userId;
    private final String name;
    private final Long totalRides;
    private final Long completedRides;
    private final Long cancelledRides;
    private final Double totalSpent;
    private final Double averageFare;

    private UserRideSummaryDTO(Builder builder) {
        this.userId = builder.userId;
        this.name = builder.name;
        this.totalRides = builder.totalRides;
        this.completedRides = builder.completedRides;
        this.cancelledRides = builder.cancelledRides;
        this.totalSpent = builder.totalSpent;
        this.averageFare = builder.averageFare;
    }

    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public Long getTotalRides() { return totalRides; }
    public Long getCompletedRides() { return completedRides; }
    public Long getCancelledRides() { return cancelledRides; }
    public Double getTotalSpent() { return totalSpent; }
    public Double getAverageFare() { return averageFare; }

    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long userId;
        private String name;
        private Long totalRides;
        private Long completedRides;
        private Long cancelledRides;
        private Double totalSpent;
        private Double averageFare;

        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder totalRides(Long totalRides) { this.totalRides = totalRides; return this; }
        public Builder completedRides(Long completedRides) { this.completedRides = completedRides; return this; }
        public Builder cancelledRides(Long cancelledRides) { this.cancelledRides = cancelledRides; return this; }
        public Builder totalSpent(Double totalSpent) { this.totalSpent = totalSpent; return this; }
        public Builder averageFare(Double averageFare) { this.averageFare = averageFare; return this; }

        public UserRideSummaryDTO build() { return new UserRideSummaryDTO(this); }
    }
}