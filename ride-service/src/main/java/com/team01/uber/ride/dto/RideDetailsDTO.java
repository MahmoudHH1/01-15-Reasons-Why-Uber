package com.team01.uber.ride.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.team01.uber.ride.enums.RideStatus;

import java.util.List;
import java.util.Map;

@JsonDeserialize(builder = RideDetailsDTO.Builder.class)
public class RideDetailsDTO {
    private final Long rideId;
    private final Long userId;
    private final Long driverId;
    private final RideStatus status;
    private final Double fare;
    private final Map<String, Object> metadata;
    private final List<StopDetailDTO> stops;
    private final int totalStops;
    private final long completedStops;

    private RideDetailsDTO(Builder builder) {
        this.rideId = builder.rideId;
        this.userId = builder.userId;
        this.driverId = builder.driverId;
        this.status = builder.status;
        this.fare = builder.fare;
        this.metadata = builder.metadata;
        this.stops = builder.stops;
        this.totalStops = builder.totalStops;
        this.completedStops = builder.completedStops;
    }

    public Long getRideId() { return rideId; }
    public Long getUserId() { return userId; }
    public Long getDriverId() { return driverId; }
    public RideStatus getStatus() { return status; }
    public Double getFare() { return fare; }
    public Map<String, Object> getMetadata() { return metadata; }
    public List<StopDetailDTO> getStops() { return stops; }
    public int getTotalStops() { return totalStops; }
    public long getCompletedStops() { return completedStops; }

    public static Builder builder() {
        return new Builder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long rideId;
        private Long userId;
        private Long driverId;
        private RideStatus status;
        private Double fare;
        private Map<String, Object> metadata;
        private List<StopDetailDTO> stops;
        private int totalStops;
        private long completedStops;

        public Builder rideId(Long rideId) {
            this.rideId = rideId;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder driverId(Long driverId) {
            this.driverId = driverId;
            return this;
        }

        public Builder status(RideStatus status) {
            this.status = status;
            return this;
        }

        public Builder fare(Double fare) {
            this.fare = fare;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder stops(List<StopDetailDTO> stops) {
            this.stops = stops;
            return this;
        }

        public Builder totalStops(int totalStops) {
            this.totalStops = totalStops;
            return this;
        }

        public Builder completedStops(long completedStops) {
            this.completedStops = completedStops;
            return this;
        }

        public RideDetailsDTO build() {
            return new RideDetailsDTO(this);
        }
    }
}