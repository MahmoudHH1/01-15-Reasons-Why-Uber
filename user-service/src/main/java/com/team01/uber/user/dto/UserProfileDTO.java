package com.team01.uber.user.dto;

import java.util.List;
import java.util.Map;

public class UserProfileDTO {

    private Long userId;
    private String name;
    private String email;
    private String phone;
    private Map<String, Object> preferences;
    private List<AddressDTO> savedAddresses;
    private int totalAddresses;

    public UserProfileDTO(Long userId, String name, String email, String phone,
                          Map<String, Object> preferences, List<AddressDTO> savedAddresses) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.preferences = preferences;
        this.savedAddresses = savedAddresses;
        this.totalAddresses = savedAddresses.size();
    }

    public Long getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public Map<String, Object> getPreferences() { return preferences; }
    public List<AddressDTO> getSavedAddresses() { return savedAddresses; }
    public int getTotalAddresses() { return totalAddresses; }
}