package com.team01.uber.user.controller;

import com.team01.uber.user.dto.UserRideSummaryDTO;
import com.team01.uber.user.dto.TopRiderDTO;
import com.team01.uber.user.dto.UserProfileDTO;
import com.team01.uber.user.model.User;
import com.team01.uber.user.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping("/{id}")
    public User getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    @PutMapping("/{id}")
    public User updateUser(@PathVariable Long id, @RequestBody User user) {
        return userService.updateUser(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
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
    public List<User> searchUsers(@RequestParam(required = false) String name, @RequestParam(required = false) String email, @RequestParam(required = false) String role) {
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
    public ResponseEntity<Void> deactivateUser(@PathVariable Long id) {
        userService.deactivateUser(id);
        return ResponseEntity.ok().build();
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
}