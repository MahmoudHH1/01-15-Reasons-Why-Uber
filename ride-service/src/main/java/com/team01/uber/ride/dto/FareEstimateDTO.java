package com.team01.uber.ride.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = FareEstimateDTO.Builder.class)
public class FareEstimateDTO {
    private final double estimatedDistance;
    private final double estimatedDuration;
    private final double estimatedFare;
    private final double surgeMultiplier;

    private FareEstimateDTO(Builder builder) {
        this.estimatedDistance = builder.estimatedDistance;
        this.estimatedDuration = builder.estimatedDuration;
        this.estimatedFare = builder.estimatedFare;
        this.surgeMultiplier = builder.surgeMultiplier;
    }

    public double getEstimatedDistance() { return estimatedDistance; }
    public double getEstimatedDuration() { return estimatedDuration; }
    public double getEstimatedFare() { return estimatedFare; }
    public double getSurgeMultiplier() { return surgeMultiplier; }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private double estimatedDistance;
        private double estimatedDuration;
        private double estimatedFare;
        private double surgeMultiplier;

        public Builder estimatedDistance(double estimatedDistance) {
            this.estimatedDistance = estimatedDistance;
            return this;
        }

        public Builder estimatedDuration(double estimatedDuration) {
            this.estimatedDuration = estimatedDuration;
            return this;
        }

        public Builder estimatedFare(double estimatedFare) {
            this.estimatedFare = estimatedFare;
            return this;
        }

        public Builder surgeMultiplier(double surgeMultiplier) {
            this.surgeMultiplier = surgeMultiplier;
            return this;
        }

        public FareEstimateDTO build() {
            return new FareEstimateDTO(this);
        }
    }
}