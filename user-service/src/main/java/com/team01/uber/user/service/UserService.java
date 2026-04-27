package com.team01.uber.user.service;

import com.team01.uber.common.observer.EntityObserver;
import com.team01.uber.common.observer.Observable;
import com.team01.uber.user.adapter.ObjectArrayDtoAdapter;
import com.team01.uber.user.dto.UserRideSummaryDTO;
import com.team01.uber.user.dto.AddressDTO;
import com.team01.uber.user.dto.TopRiderDTO;
import com.team01.uber.user.dto.UserProfileDTO;
import com.team01.uber.user.model.mongo.AuthEvent;
import com.team01.uber.user.model.SavedAddress;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.observer.MongoEventLogger;
import com.team01.uber.user.repository.SavedAddressRepository;
import com.team01.uber.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class UserService implements Observable{

    private final UserRepository userRepository;
    private final SavedAddressRepository savedAddressRepository;
    private final List<EntityObserver> observers = new ArrayList<>();
    private final ObjectArrayDtoAdapter objectArrayDtoAdapter = new ObjectArrayDtoAdapter();

    public UserService(UserRepository userRepository, SavedAddressRepository savedAddressRepository, MongoEventLogger mongoEventLogger) {
        this.savedAddressRepository = savedAddressRepository;
        this.userRepository = userRepository;
        registerObserver(mongoEventLogger);
    }

    @Override
    public void registerObserver(EntityObserver observer) {
        observers.add(observer);
    }

    @Override
    public void notifyObservers(String action, Map<String, Object> payload) {
        observers.forEach(o -> o.onEvent(action, payload));
    }

    @Override
    public void unregisterObserver(EntityObserver observer) {
        observers.remove(observer);
    }

    public User createUser(User user) {
        if (userRepository.existsByEmail(user.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        if (userRepository.existsByPhone(user.getPhone()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already exists");
        User saved = userRepository.save(user);
        notifyObservers(AuthEvent.ACTION_USER_CREATED, Map.of("userId", saved.getId(), "email", saved.getEmail()));
        return saved;
    }

    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User updateUser(Long id, User updated) {
        User existing = getUserById(id);

        validateRequiredUpdateKeys(updated);

        existing.setName(updated.getName());
        existing.setEmail(updated.getEmail());
        existing.setPassword(updated.getPassword());
        existing.setPhone(updated.getPhone());
        existing.setRole(updated.getRole());
        existing.setStatus(updated.getStatus());
        existing.setPreferences(updated.getPreferences());

        User saved = userRepository.save(existing);
        notifyObservers(AuthEvent.ACTION_USER_UPDATED, Map.of("userId", saved.getId()));
        return saved;
    }

    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.deleteById(id);
        notifyObservers(AuthEvent.ACTION_USER_DELETED, Map.of("userId", id));
    }

    private void validateRequiredUpdateKeys(User updated) {
        if (updated.getName() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be null");
        if (updated.getEmail() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email cannot be null");
        if (updated.getPassword() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password cannot be null");
        if (updated.getPhone() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone cannot be null");
        if (updated.getRole() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role cannot be null");
        if (updated.getStatus() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status cannot be null");
    }

    public UserRideSummaryDTO getRideSummary(Long userId) {
        getUserById(userId);
        Object[] row = userRepository.getRideSummary(userId);
        if (row == null || row.length == 0) {
            return UserRideSummaryDTO.builder()
                    .userId(userId)
                    .name(null)
                    .totalRides(0L)
                    .completedRides(0L)
                    .cancelledRides(0L)
                    .totalSpent(0.0)
                    .averageFare(0.0)
                    .build();
        }
        Object[] data = (Object[]) row[0];
        return objectArrayDtoAdapter.adaptToUserRideSummary(data);
    }

    public User updatePreferences(Long id, Map<String, Object> incoming) {
        User user = getUserById(id);
        Map<String, Object> current = user.getPreferences();
        if (current == null) {
            user.setPreferences(incoming);
        } else {
            current.putAll(incoming);
            user.setPreferences(current);
        }
        User saved= userRepository.save(user);
        notifyObservers(AuthEvent.ACTION_USER_UPDATED, Map.of("userId", saved.getId(), "updatedKeys", incoming.keySet()));
        return saved;
    }

    public List<User> searchUsers(String name, String email, String role) {
        return userRepository.searchUsers(name, email, role);
    }

    public List<TopRiderDTO> getTopRiders(String startDate, String endDate, int limit) {
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDate.parse(startDate).atStartOfDay();
            end = LocalDate.parse(endDate).atTime(23, 59, 59);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format. Use yyyy-MM-dd");
        }
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must not be after endDate");
        }
        return userRepository.findTopRiders(start, end, limit)
                .stream()
                .map(row -> objectArrayDtoAdapter.adaptToTopRider((Object[]) row))
                .toList();
    }

    public List<User> searchByPreference(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Key and value must not be blank");
        }
        return userRepository.findByPreference(key, value);
    }

    @Transactional
    public User deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with id: " + userId));
        if (userRepository.countActiveRides(userId) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has active rides and cannot be deactivated");
        }
        user.setStatus(UserStatus.DEACTIVATED);
        User saved = userRepository.save(user);
        notifyObservers(AuthEvent.ACTION_USER_DEACTIVATED, Map.of("userId", saved.getId()));
        return saved;    }

    @Transactional
    public User setDefaultAddress(Long userId, Long addressId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        SavedAddress target = savedAddressRepository.findById(addressId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));

        if (!target.getUser().getId().equals(userId)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Address does not belong to this user");
        }

        savedAddressRepository.clearDefaultForUser(userId);
        target.setIsDefault(true);
        savedAddressRepository.save(target);
        notifyObservers(AuthEvent.ACTION_DEFAULT_ADDRESS_SET, Map.of("userId", userId, "addressId", addressId));
        return userRepository.findById(userId).get();
    }
    public List<User> findUsersByLanguageWithMinRides(String lang, int minRides) {
        if (lang == null || lang.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lang must not be blank");
        }
        return userRepository.findByLanguagePreferenceWithMinRides(lang, minRides);
    }

    public UserProfileDTO getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<AddressDTO> addressDTOs = user.getSavedAddresses().stream()
                .map(addr -> AddressDTO.builder()
                        .id(addr.getId())
                        .label(addr.getLabel())
                        .address(addr.getAddress())
                        .latitude(addr.getLatitude())
                        .longitude(addr.getLongitude())
                        .isDefault(addr.getIsDefault())
                        .metadata(addr.getMetadata())
                        .build())
                .toList();

        return UserProfileDTO.builder()
                .userId(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .preferences(user.getPreferences())
                .savedAddresses(addressDTOs)
                .totalAddresses(addressDTOs.size())
                .build();
    }   


}