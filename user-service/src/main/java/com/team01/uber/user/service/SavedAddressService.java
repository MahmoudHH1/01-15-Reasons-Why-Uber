package com.team01.uber.user.service;

import com.team01.uber.user.model.SavedAddress;
import com.team01.uber.user.model.User;
import com.team01.uber.user.repository.SavedAddressRepository;
import com.team01.uber.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SavedAddressService {

    private final SavedAddressRepository savedAddressRepository;
    private final UserRepository userRepository;

    public SavedAddressService(SavedAddressRepository savedAddressRepository, UserRepository userRepository) {
        this.savedAddressRepository = savedAddressRepository;
        this.userRepository = userRepository;
    }

    public SavedAddress createAddress(Long userId, SavedAddress address) {
        User user = getUserById(userId);
        address.setUser(user);
        address.setCreatedAt(LocalDateTime.now());
        return savedAddressRepository.save(address);
    }

    public List<SavedAddress> getAllAddresses(Long userId) {
        getUserById(userId); // validate user exists
        return savedAddressRepository.findByUserId(userId);
    }

    public SavedAddress getAddressById(Long userId, Long id) {
        return savedAddressRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
    }

    public SavedAddress updateAddress(Long userId, Long id, SavedAddress updated) {
        SavedAddress existing = getAddressById(userId, id);

        validateRequiredUpdateKeys(updated);

        existing.setLabel(updated.getLabel());
        existing.setAddress(updated.getAddress());
        existing.setLatitude(updated.getLatitude());
        existing.setLongitude(updated.getLongitude());
        existing.setIsDefault(updated.getIsDefault());
        existing.setMetadata(updated.getMetadata());

        return savedAddressRepository.save(existing);
    }

    public void deleteAddress(Long userId, Long id) {
        SavedAddress address = getAddressById(userId, id);
        savedAddressRepository.delete(address);
    }

    private User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private void validateRequiredUpdateKeys(SavedAddress updated) {
        if (updated.getLabel() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Label cannot be null");
        if (updated.getAddress() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Address cannot be null");
        if (updated.getLatitude() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Latitude cannot be null");
        if (updated.getLongitude() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Longitude cannot be null");
        if (updated.getIsDefault() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "isDefault cannot be null");
    }
}