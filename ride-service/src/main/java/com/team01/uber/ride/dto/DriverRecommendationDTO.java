package com.team01.uber.ride.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = DriverRecommendationDTO.Builder.class)
public class DriverRecommendationDTO {
    private final Long driverId;
    private final String name;
    private final String vehicleType;
    private final int score;

    // Private constructor enforcing the use of the Builder
    private DriverRecommendationDTO(Builder builder) {
        this.driverId = builder.driverId;
        this.name = builder.name;
        this.vehicleType = builder.vehicleType;
        this.score = builder.score;
    }

    // Getters
    public Long getDriverId() { return driverId; }
    public String getName() { return name; }
    public String getVehicleType() { return vehicleType; }
    public int getScore() { return score; }

    // Static method to initiate the builder
    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long driverId;
        private String name;
        private String vehicleType;
        private int score;

        public Builder driverId(Long driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder vehicleType(String vehicleType) {
            this.vehicleType = vehicleType;
            return this;
        }

        public Builder score(int score) {
            this.score = score;
            return this;
        }

        public DriverRecommendationDTO build() {
            return new DriverRecommendationDTO(this);
        }
    }
}