package com.team01.uber.payment.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

@JsonDeserialize(builder = VehicleTypeRevenueDTO.Builder.class)
public class VehicleTypeRevenueDTO {

    private String vehicleType;
    private Double baseFareRevenue;
    private Double surgeFeeRevenue;
    private Double totalRevenue;
    private Long rideCount;

    private VehicleTypeRevenueDTO(Builder builder) {
        this.vehicleType     = builder.vehicleType;
        this.baseFareRevenue = builder.baseFareRevenue;
        this.surgeFeeRevenue = builder.surgeFeeRevenue;
        this.totalRevenue    = builder.totalRevenue;
        this.rideCount       = builder.rideCount;
    }

    public String getVehicleType()     { return vehicleType; }
    public Double getBaseFareRevenue() { return baseFareRevenue; }
    public Double getSurgeFeeRevenue() { return surgeFeeRevenue; }
    public Double getTotalRevenue()    { return totalRevenue; }
    public Long getRideCount()         { return rideCount; }


    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private String vehicleType;
        private Double baseFareRevenue;
        private Double surgeFeeRevenue;
        private Double totalRevenue;
        private Long rideCount;

        public Builder vehicleType(String vehicleType)         { this.vehicleType = vehicleType; return this; }
        public Builder baseFareRevenue(Double baseFareRevenue) { this.baseFareRevenue = baseFareRevenue; return this; }
        public Builder surgeFeeRevenue(Double surgeFeeRevenue) { this.surgeFeeRevenue = surgeFeeRevenue; return this; }
        public Builder totalRevenue(Double totalRevenue)       { this.totalRevenue = totalRevenue; return this; }
        public Builder rideCount(Long rideCount)               { this.rideCount = rideCount; return this; }

        public VehicleTypeRevenueDTO build() {
            return new VehicleTypeRevenueDTO(this);
        }
    }
}