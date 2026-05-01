package com.team01.uber.user.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;

import java.util.List;
import java.util.Map;

@JsonDeserialize(builder = UserProfileDTO.Builder.class)
public class UserProfileDTO {

    private final Long userId;
    private final String name;
    private final String email;
    private final String phone;
    private final Map<String, Object> preferences;
    private final List<AddressDTO> savedAddresses;
    private final int totalAddresses;

    private UserProfileDTO(Builder builder) {
        this.userId = builder.userId;
        this.name = builder.name;
        this.email = builder.email;
        this.phone = builder.phone;
        this.preferences = builder.preferences;
        this.savedAddresses = builder.savedAddresses;
        this.totalAddresses = builder.totalAddresses;
    }

    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public Map<String, Object> getPreferences() { return preferences; }
    public List<AddressDTO> getSavedAddresses() { return savedAddresses; }
    public int getTotalAddresses() { return totalAddresses; }

    public static Builder builder() { return new Builder(); }

    @JsonPOJOBuilder(withPrefix = "")
    public static class Builder {
        private Long userId;
        private String name;
        private String email;
        private String phone;
        private Map<String, Object> preferences;
        private List<AddressDTO> savedAddresses;
        private int totalAddresses;

        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder phone(String phone) { this.phone = phone; return this; }
        public Builder preferences(Map<String, Object> preferences) { this.preferences = preferences; return this; }
        public Builder savedAddresses(List<AddressDTO> savedAddresses) { this.savedAddresses = savedAddresses; return this; }
        public Builder totalAddresses(int totalAddresses) { this.totalAddresses = totalAddresses; return this; }

        public UserProfileDTO build() { return new UserProfileDTO(this); }
    }
}