package com.team01.uber.user.service;

import com.team01.uber.user.adapter.MongoDocumentAdapter;
import com.team01.uber.user.dto.*;
import com.team01.uber.user.observer.EntityObserver;
import com.team01.uber.user.observer.Observable;
import com.team01.uber.user.adapter.ObjectArrayDtoAdapter;
import com.team01.uber.user.model.mongo.AuthEvent;
import com.team01.uber.user.model.SavedAddress;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.observer.MongoEventLogger;
import com.team01.uber.user.repository.AuthEventRepository;
import com.team01.uber.user.repository.SavedAddressRepository;
import com.team01.uber.user.repository.UserRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import com.team01.uber.contracts.feign.PaymentServiceClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.team01.uber.user.messaging.publishers.UserEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.team01.uber.contracts.feign.RideServiceClient;
import com.team01.uber.contracts.dto.RideSummaryDTO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.team01.uber.user.messaging.publishers.UserEventPublisher;
import com.team01.uber.contracts.feign.PaymentServiceClient;
import java.math.BigDecimal;

@Service
public class UserService implements Observable {

    private final UserRepository userRepository;
    private final AuthEventRepository authEventRepository;
    private final MongoDocumentAdapter mongoDocumentAdapter = new MongoDocumentAdapter();
    private final SavedAddressRepository savedAddressRepository;
    private final List<EntityObserver> observers = new ArrayList<>();
    private final ObjectArrayDtoAdapter objectArrayDtoAdapter = new ObjectArrayDtoAdapter();
    private final UserEventPublisher userEventPublisher;       
    private final RideServiceClient rideServiceClient;             
    private final PaymentServiceClient paymentServiceClient;       

    public UserService(UserRepository userRepository,
                       SavedAddressRepository savedAddressRepository,
                       MongoEventLogger mongoEventLogger,
                       AuthEventRepository authEventRepository,
                        UserEventPublisher userEventPublisher,
                        RideServiceClient rideServiceClient,
                        PaymentServiceClient paymentServiceClient) {
        this.savedAddressRepository = savedAddressRepository;
        this.userRepository = userRepository;
        this.authEventRepository = authEventRepository;
        this.userEventPublisher = userEventPublisher;
        this.rideServiceClient = rideServiceClient;
        this.paymentServiceClient = paymentServiceClient; 
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

    @Caching(evict = {
            @CacheEvict(value = "user-service::S1-F1", allEntries = true),
            @CacheEvict(value = "user-service::S1-F6", allEntries = true)
    })
    public User createUser(User user) {
        if (userRepository.existsByEmail(user.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        if (userRepository.existsByPhone(user.getPhone()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already exists");
        User saved = userRepository.save(user);
        notifyObservers(AuthEvent.ACTION_USER_CREATED,
                Map.of("userId", saved.getId(), "email", saved.getEmail()));
        
        // Publish RabbitMQ event
        userEventPublisher.publishUserRegistered(saved.getId(), saved.getEmail(), saved.getRole().name());
        
        return saved;
    }

    // No @Cacheable — User entity is a JPA entity with relationships,
    // not safely serializable to Redis. API-facing DTOs are cached instead.
@Cacheable(value = "user-service::user", key = "#id")

    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Caching(evict = {
            @CacheEvict(value = "user-service::S1-F1", allEntries = true),
            @CacheEvict(value = "user-service::S1-F3", key = "#id"),
            @CacheEvict(value = "user-service::S1-F5", allEntries = true),
            @CacheEvict(value = "user-service::S1-F6", allEntries = true),
            @CacheEvict(value = "user-service::S1-F8", key = "#id"),
            @CacheEvict(value = "user-service::S1-F9", allEntries = true),
            @CacheEvict(value = "user-service::S1-F12", allEntries = true)
    })
    public User updateUser(Long id, User updated) {
        User existing = getUserById(id);
        if (updated.getName() != null) existing.setName(updated.getName());
        if (updated.getEmail() != null) existing.setEmail(updated.getEmail());
        if (updated.getPassword() != null) existing.setPassword(updated.getPassword());
        if (updated.getPhone() != null) existing.setPhone(updated.getPhone());
        if (updated.getRole() != null) existing.setRole(updated.getRole());
        if (updated.getStatus() != null) existing.setStatus(updated.getStatus());
        if (updated.getPreferences() != null) existing.setPreferences(updated.getPreferences());
        User saved = userRepository.save(existing);
        notifyObservers(AuthEvent.ACTION_USER_UPDATED, Map.of("userId", saved.getId()));
        return saved;
    }

    @Caching(evict = {
            @CacheEvict(value = "user-service::S1-F1", allEntries = true),
            @CacheEvict(value = "user-service::S1-F6", allEntries = true)
    })
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

    @Cacheable(value = "user-service::S1-F3", key = "#userId")
    public UserRideSummaryDTO getRideSummary(Long userId) {
        User user = getUserById(userId);
        
        try {
            RideSummaryDTO summary = rideServiceClient.getUserRideSummary(userId);
            return UserRideSummaryDTO.builder()
                    .userId(userId)
                    .name(user.getName())
                    .totalRides(summary.totalRides())
                    .completedRides(summary.completedRides())
                    .cancelledRides(summary.cancelledRides())
                    .totalSpent(summary.totalSpent())
                    .averageFare(summary.averageFare())
                    .build();
        } catch (feign.FeignException.NotFound e) {
            // User has no rides yet
            return UserRideSummaryDTO.builder()
                    .userId(userId)
                    .name(user.getName())
                    .totalRides(0L)
                    .completedRides(0L)
                    .cancelledRides(0L)
                    .totalSpent(0.0)
                    .averageFare(0.0)
                    .build();
        } catch (feign.FeignException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, 
                    "Ride service unavailable: " + e.getMessage());
        }
    }

    @Caching(evict = {
            @CacheEvict(value = "user-service::S1-F3", key = "#id"),
            @CacheEvict(value = "user-service::S1-F8", key = "#id"),
            @CacheEvict(value = "user-service::S1-F9", allEntries = true),
            @CacheEvict(value = "user-service::S1-F12", allEntries = true)
    })
    public User updatePreferences(Long id, Map<String, Object> incoming) {
        User user = getUserById(id);
        Map<String, Object> current = user.getPreferences();
        if (current == null) {
            user.setPreferences(incoming);
        } else {
            current.putAll(incoming);
            user.setPreferences(current);
        }
        User saved = userRepository.save(user);
        notifyObservers(AuthEvent.ACTION_USER_UPDATED,
                Map.of("userId", saved.getId(), "updatedKeys", incoming.keySet()));
        return saved;
    }

    @Cacheable(value = "user-service::S1-F1", key = "#name + '-' + #email + '-' + #role")
    public List<User> searchUsers(String name, String email, String role) {
        return userRepository.searchUsers(name, email, role);
    }

    @Cacheable(value = "user-service::S1-F6", key = "#startDate + '-' + #endDate + '-' + #limit")
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "startDate must not be after endDate");
        }
        
        // Fetch all users (cap at 100 to avoid N+1 explosion)
        List<User> candidates = userRepository.findAll().stream().limit(100).toList();
        
        // Per-user Feign calls to payment-service
        List<TopRiderDTO> riders = new ArrayList<>();
        for (User user : candidates) {
            try {
                BigDecimal totalSpent = paymentServiceClient.getUserTotalPayments(
                        user.getId(), startDate, endDate);
                if (totalSpent.compareTo(BigDecimal.ZERO) > 0) {
                    riders.add(TopRiderDTO.builder()
                            .userId(user.getId())
                            .name(user.getName())
                            .totalSpent(totalSpent.doubleValue())
                            .build());
                }
            } catch (feign.FeignException.NotFound e) {
                // User has no payments in this period, skip
            } catch (feign.FeignException e) {
                // Log warning but continue with other users
            }
        }
        
        // Sort by totalSpent descending and return top N
        return riders.stream()
                .sorted((a, b) -> Double.compare(b.totalSpent(), a.totalSpent()))
                .limit(limit)
                .toList();
    }

    @Cacheable(value = "user-service::S1-F5", key = "#key + '-' + #value")
    public List<User> searchByPreference(String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Key and value must not be blank");
        }
        return userRepository.findByPreference(key, value);
    }

    @Caching(evict = {
            @CacheEvict(value = "user-service::S1-F1", allEntries = true),
            @CacheEvict(value = "user-service::S1-F12", allEntries = true)
    })
    @Transactional
    public User deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found with id: " + userId));
        
        // Idempotent check: if already deactivated, skip re-publication
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            return user;  // 200 OK, no event
        }
        
        // Feign call to check active rides
        try {
            int activeRideCount = rideServiceClient.getActiveRideCount(userId);
            if (activeRideCount > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "User has active rides and cannot be deactivated");
            }
        } catch (feign.FeignException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cannot verify ride status: " + e.getMessage());
        }
        
        user.setStatus(UserStatus.DEACTIVATED);
        User saved = userRepository.save(user);
        notifyObservers(AuthEvent.ACTION_USER_DEACTIVATED, Map.of("userId", saved.getId()));
        
        // Publish RabbitMQ event
        userEventPublisher.publishUserDeactivated(saved.getId());
        
        return saved;
    }
    @Caching(evict = {
            @CacheEvict(value = "user-service::savedAddress", key = "#addressId"),
            @CacheEvict(value = "user-service::S1-F8", key = "#userId"),
            @CacheEvict(value = "user-service::S1-F9", allEntries = true)
    })
    @Transactional
    public User setDefaultAddress(Long userId, Long addressId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        SavedAddress target = savedAddressRepository.findById(addressId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
        if (!target.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Address does not belong to this user");
        }
        savedAddressRepository.clearDefaultForUser(userId);
        target.setIsDefault(true);
        savedAddressRepository.save(target);
        notifyObservers(AuthEvent.ACTION_DEFAULT_ADDRESS_SET,
                Map.of("userId", userId, "addressId", addressId));
        return userRepository.findById(userId).get();
    }

    @Cacheable(value = "user-service::S1-F9", key = "#lang + '-' + #minRides")
    public List<User> findUsersByLanguageWithMinRides(String lang, int minRides) {
        if (lang == null || lang.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lang must not be blank");
        }
        
        // Fetch users by language preference (cap at 100)
        List<User> candidates =userRepository.findByPreference("language", lang).stream().limit(100).toList();
        
        // Per-user Feign calls to ride-service
        List<User> qualified = new ArrayList<>();
        for (User user : candidates) {
            try {
                long completedCount = rideServiceClient.getCompletedRideCount(user.getId());
                if (completedCount >= minRides) {
                    qualified.add(user);
                }
            } catch (feign.FeignException.NotFound e) {
                // User has no rides, skip
            } catch (feign.FeignException e) {
                // Log warning but continue with other users
            }
        }
        
        return qualified;
    }

    @Cacheable(value = "user-service::S1-F8", key = "#userId")
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

    @Cacheable(value = "user-service::S1-F12", key = "#userId + '-' + #page + '-' + #size")
    public ActivityFeedDTO getActivityFeed(Long userId, int page, int size) {

        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page index must not be less than zero");
        }
        if (size <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must not be less than one");
        }

        User authenticatedUser = (User) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        if (!authenticatedUser.getId().equals(userId) &&
                !authenticatedUser.getRole().name().equals("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        getUserById(userId);
        int cappedSize = Math.min(size, 100);
        Pageable pageable = PageRequest.of(page, cappedSize);
        Page<AuthEvent> events = authEventRepository.findByUserIdOrderByTimestampDesc(userId, pageable);
        List<ActivityEventDTO> content = events.getContent()
                .stream()
                .map(mongoDocumentAdapter::adapt)
                .toList();
        return ActivityFeedDTO.builder()
                .content(content)
                .page(page)
                .size(cappedSize)
                .totalElements(events.getTotalElements())
                .build();
    }

    @Caching(evict = {
            @CacheEvict(value = "user-service::S1-F8", key = "#id"),
            @CacheEvict(value = "user-service::S1-F12", allEntries = true)
    })
    public User updateUserRole(Long id, String newRole) {
        User user = getUserById(id);
        try {
            com.team01.uber.user.model.UserRole role =
                    com.team01.uber.user.model.UserRole.valueOf(newRole.toUpperCase());
            String oldRole = user.getRole().name();
            user.setRole(role);
            User saved = userRepository.save(user);
            notifyObservers(AuthEvent.ACTION_ROLE_CHANGED,
                    Map.of("userId", saved.getId(), "oldRole", oldRole, "newRole", role.name()));
            return saved;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role value");
        }
    }
}