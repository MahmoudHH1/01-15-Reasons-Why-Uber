package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriverEarningsDTO {

    private Long driverId;
    private String name;
    private Long totalRides;
    private Double totalEarnings;
    private Double averageFare;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long driverId;
        private String name;
        private Long totalRides;
        private Double totalEarnings;
        private Double averageFare;

        public Builder driverId(Long driverId) { this.driverId = driverId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder totalRides(Long totalRides) { this.totalRides = totalRides; return this; }
        public Builder totalEarnings(Double totalEarnings) { this.totalEarnings = totalEarnings; return this; }
        public Builder averageFare(Double averageFare) { this.averageFare = averageFare; return this; }

        public DriverEarningsDTO build() {
            return new DriverEarningsDTO(driverId, name, totalRides, totalEarnings, averageFare);
        }
    }
}
