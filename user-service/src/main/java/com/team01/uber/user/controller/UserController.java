package com.team01.uber.user.controller;

import com.team01.uber.user.dto.ActivityFeedDTO;
import com.team01.uber.user.dto.UserRideSummaryDTO;
import com.team01.uber.user.dto.TopRiderDTO;
import com.team01.uber.user.dto.UserProfileDTO;
import com.team01.uber.user.model.User;
import com.team01.uber.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(user));
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<User> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String newRole = request.get("role");
        if (newRole == null || newRole.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(userService.updateUserRole(id, newRole));
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> getUserById(@PathVariable Long id) {
        checkOwnership(id);
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    @PutMapping("/{id}")
    public User updateUser(@PathVariable Long id, @RequestBody User user) {
        checkOwnership(id);
        return userService.updateUser(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        checkOwnership(id);
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/ride-summary")
    public UserRideSummaryDTO getRideSummary(@PathVariable Long id) {
        return userService.getRideSummary(id);
    }

    @PutMapping("/{id}/preferences")
    public User updatePreferences(@PathVariable Long id, @RequestBody Map<String, Object> preferences) {
        return userService.updatePreferences(id, preferences);
    }

    @GetMapping("/search")
    public List<User> searchUsers(@RequestParam(required = false) String name,
                                   @RequestParam(required = false) String email,
                                   @RequestParam(required = false) String role) {
        return userService.searchUsers(name, email, role);
    }

    @GetMapping("/reports/top-riders")
    public ResponseEntity<List<TopRiderDTO>> getTopRiders(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam int limit) {
        return ResponseEntity.ok(userService.getTopRiders(startDate, endDate, limit));
    }

    @GetMapping("/preferences/search")
    public ResponseEntity<List<User>> searchByPreference(
            @RequestParam String key,
            @RequestParam String value) {
        return ResponseEntity.ok(userService.searchByPreference(key, value));
    }

    @PutMapping("/{id}/deactivate")
    public ResponseEntity<User> deactivateUser(@PathVariable Long id) {
        return ResponseEntity.ok(userService.deactivateUser(id));
    }

    @PutMapping("/{userId}/addresses/{addressId}/default")
    public ResponseEntity<User> setDefaultAddress(
            @PathVariable Long userId,
            @PathVariable Long addressId) {
        return ResponseEntity.ok(userService.setDefaultAddress(userId, addressId));
    }

    @GetMapping("/preferences/language")
    public ResponseEntity<List<User>> getUsersByLanguage(
            @RequestParam String lang,
            @RequestParam int minRides) {
        return ResponseEntity.ok(userService.findUsersByLanguageWithMinRides(lang, minRides));
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<UserProfileDTO> getUserProfile(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserProfile(id));
    }

    @GetMapping("/{id}/activity")
    public ResponseEntity<ActivityFeedDTO> getActivityFeed(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(userService.getActivityFeed(id, page, size));
    }

    private void checkOwnership(Long targetId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        User caller = (User) auth.getPrincipal();
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (!isAdmin && !caller.getId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }
}