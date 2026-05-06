package com.team01.uber.driver.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TopDriverDTO {

    private Long driverId;
    private String name;
    private Double rating;
    private Long totalRides;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long driverId;
        private String name;
        private Double rating;
        private Long totalRides;

        public Builder driverId(Long driverId) { this.driverId = driverId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder rating(Double rating) { this.rating = rating; return this; }
        public Builder totalRides(Long totalRides) { this.totalRides = totalRides; return this; }

        public TopDriverDTO build() {
            return new TopDriverDTO(driverId, name, rating, totalRides);
        }
    }
}
