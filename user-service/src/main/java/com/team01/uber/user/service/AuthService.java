package com.team01.uber.user.service;

import com.team01.uber.user.dto.AuthResponse;
import com.team01.uber.user.dto.RegisterRequest;
import com.team01.uber.user.model.AuthEvent;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserRole;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.repository.AuthEventRepository;
import com.team01.uber.user.repository.UserRepository;
import com.team01.uber.user.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthEventRepository authEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       AuthEventRepository authEventRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.authEventRepository = authEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        if (request.getName() == null || request.getName().isBlank() ||
            request.getEmail() == null || request.getEmail().isBlank() ||
            request.getPassword() == null || request.getPassword().isBlank() ||
            request.getPhone() == null || request.getPhone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name, email, password and phone are required");
        }

        if (userRepository.existsByEmail(request.getEmail()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");

        if (userRepository.existsByPhone(request.getPhone()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone already registered");

        User user = new User();
        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setPhone(request.getPhone());
        user.setRole(UserRole.RIDER);
        user.setStatus(UserStatus.ACTIVE);

        user = userRepository.save(user);

        try {
            AuthEvent event = new AuthEvent(
                    user.getId(),
                    "REGISTERED",
                    LocalDateTime.now(),
                    Map.of("email", user.getEmail())
            );
            authEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to log REGISTERED event to MongoDB: {}", e.getMessage());
        }

        String token = jwtService.generateToken(user);
        return new AuthResponse(token, jwtService.getExpirationMs());
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);
}