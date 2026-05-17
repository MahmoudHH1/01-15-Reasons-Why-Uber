package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriverDashboardDTO {

    private Long driverId;
    private String name;
    private Long totalRides;
    private Double totalEarnings;
    private Double averageRideFare;
    private Double averageRating;
    private Integer totalRatings;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long driverId;
        private String name;
        private Long totalRides;
        private Double totalEarnings;
        private Double averageRideFare;
        private Double averageRating;
        private Integer totalRatings;

        public Builder driverId(Long driverId) { this.driverId = driverId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder totalRides(Long totalRides) { this.totalRides = totalRides; return this; }
        public Builder totalEarnings(Double totalEarnings) { this.totalEarnings = totalEarnings; return this; }
        public Builder averageRideFare(Double averageRideFare) { this.averageRideFare = averageRideFare; return this; }
        public Builder averageRating(Double averageRating) { this.averageRating = averageRating; return this; }
        public Builder totalRatings(Integer totalRatings) { this.totalRatings = totalRatings; return this; }

        public DriverDashboardDTO build() {
            return new DriverDashboardDTO(driverId, name, totalRides, totalEarnings, averageRideFare, averageRating, totalRatings);
        }
    }
}
