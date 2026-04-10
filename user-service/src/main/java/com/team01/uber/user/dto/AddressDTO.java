package com.team01.uber.user.dto;

import java.util.Map;

public class AddressDTO {

    private Long id;
    private String label;
    private String address;
    private Double latitude;
    private Double longitude;
    private Boolean isDefault;
    private Map<String, Object> metadata;

    public AddressDTO(Long id, String label, String address,
                      Double latitude, Double longitude,
                      Boolean isDefault, Map<String, Object> metadata) {
        this.id = id;
        this.label = label;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isDefault = isDefault;
        this.metadata = metadata;
    }

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public String getAddress() { return address; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Boolean getIsDefault() { return isDefault; }
    public Map<String, Object> getMetadata() { return metadata; }
}