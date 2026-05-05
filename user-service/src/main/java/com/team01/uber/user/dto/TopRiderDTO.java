package com.team01.uber.user.dto;

public record TopRiderDTO(
        Long userId,
        String name,
        Double totalSpent,
        Long rideCount
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long userId;
        private String name;
        private Double totalSpent;
        private Long rideCount;

        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder totalSpent(Double totalSpent) { this.totalSpent = totalSpent; return this; }
        public Builder rideCount(Long rideCount) { this.rideCount = rideCount; return this; }

        public TopRiderDTO build() {
            return new TopRiderDTO(userId, name, totalSpent, rideCount);
        }
    }
}
