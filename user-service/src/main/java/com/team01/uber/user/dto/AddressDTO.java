package com.team01.uber.user.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.Map;

@JsonDeserialize(builder = AddressDTO.Builder.class)
public class AddressDTO {

    private final Long id;
    private final String label;
    private final String address;
    private final Double latitude;
    private final Double longitude;
    private final Boolean isDefault;
    private final Map<String, Object> metadata;

    private AddressDTO(Builder builder) {
        this.id = builder.id;
        this.label = builder.label;
        this.address = builder.address;
        this.latitude = builder.latitude;
        this.longitude = builder.longitude;
        this.isDefault = builder.isDefault;
        this.metadata = builder.metadata;
    }

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public String getAddress() { return address; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Boolean getIsDefault() { return isDefault; }
    public Map<String, Object> getMetadata() { return metadata; }

    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long id;
        private String label;
        private String address;
        private Double latitude;
        private Double longitude;
        private Boolean isDefault;
        private Map<String, Object> metadata;

        public Builder id(Long id) { this.id = id; return this; }
        public Builder label(String label) { this.label = label; return this; }
        public Builder address(String address) { this.address = address; return this; }
        public Builder latitude(Double latitude) { this.latitude = latitude; return this; }
        public Builder longitude(Double longitude) { this.longitude = longitude; return this; }
        public Builder isDefault(Boolean isDefault) { this.isDefault = isDefault; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public AddressDTO build() { return new AddressDTO(this); }
    }
}