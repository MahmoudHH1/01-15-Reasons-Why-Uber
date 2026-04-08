package com.team01.uber.user.service;

import com.team01.uber.user.dto.UserRideSummaryDTO;
import com.team01.uber.user.model.User;
import com.team01.uber.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User createUser(User user) {
        if (userRepository.existsByEmail(user.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        if (userRepository.existsByPhone(user.getPhone()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already exists");
        return userRepository.save(user);
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

        return userRepository.save(existing);
    }

    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        userRepository.deleteById(id);
    }

    private void validateRequiredUpdateKeys(User updated) {
        if (updated.getName() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be null");
        }
        if (updated.getEmail() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email cannot be null");
        }
        if (updated.getPassword() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password cannot be null");
        }
        if (updated.getPhone() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone cannot be null");
        }
        if (updated.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role cannot be null");
        }
        if (updated.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status cannot be null");
        }
    }

    public UserRideSummaryDTO getRideSummary(Long userId) {
    getUserById(userId);
    Object[] row = userRepository.getRideSummary(userId);
    if (row == null || row.length == 0) {
        return new UserRideSummaryDTO(userId, null, 0L, 0L, 0L, 0.0, 0.0);
    }
    Object[] data = (Object[]) row[0];
    return new UserRideSummaryDTO(
        ((Number) data[0]).longValue(),
        (String) data[1],
        ((Number) data[2]).longValue(),
        ((Number) data[3]).longValue(),
        ((Number) data[4]).longValue(),
        ((Number) data[5]).doubleValue(),
        ((Number) data[6]).doubleValue()
    );
}
}