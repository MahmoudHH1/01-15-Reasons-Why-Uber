package com.team01.uber.user.service;

import com.team01.uber.user.observer.EntityObserver;
import com.team01.uber.user.observer.Observable;
import com.team01.uber.user.dto.AuthResponse;
import com.team01.uber.user.dto.RegisterRequest;
import com.team01.uber.user.model.mongo.AuthEvent;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserRole;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.observer.MongoEventLogger;
import com.team01.uber.user.repository.AuthEventRepository;
import com.team01.uber.user.repository.UserRepository;
import com.team01.uber.user.messaging.publishers.UserEventPublisher;
import com.team01.uber.user.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.team01.uber.user.dto.LoginRequest;
import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AuthService implements Observable {

    private final UserRepository userRepository;
    private final AuthEventRepository authEventRepository;
    private final PasswordEncoder passwordEncoder;
    private final MongoEventLogger mongoEventLogger;
    private final JwtService jwtService;
    private final UserEventPublisher userEventPublisher;
    private final List<EntityObserver> observers = new ArrayList<>();

    public AuthService(UserRepository userRepository,
                       AuthEventRepository authEventRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       MongoEventLogger mongoEventLogger,
                       UserEventPublisher userEventPublisher) {
        this.userRepository = userRepository;
        this.authEventRepository = authEventRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mongoEventLogger = mongoEventLogger;  // Initialize the field
        this.userEventPublisher = userEventPublisher;
        registerObserver(mongoEventLogger);
    }
    
    @Override
    public void registerObserver(EntityObserver observer) {
        observers.add(observer);
    }

    @Override
    public void unregisterObserver(EntityObserver observer) {
        observers.remove(observer);
    }

    @Override
    public void notifyObservers(String action, Map<String, Object> payload) {
        observers.forEach(o -> o.onEvent(action, payload));
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

        // Publish user.registered event for other services to consume
        try {
            userEventPublisher.publishUserRegistered(user.getId(), user.getEmail(), user.getRole().name());
        } catch (Exception e) {
            log.warn("Failed to publish user.registered for userId={}: {}", user.getId(), e.getMessage());
        }

        notifyObservers(AuthEvent.ACTION_REGISTERED, Map.of("userId", user.getId(), "email", user.getEmail()));

        String token = jwtService.generateToken(user);
        return new AuthResponse(token, jwtService.getExpirationMs());
    }
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        notifyObservers(AuthEvent.ACTION_LOGGED_IN, Map.of("userId", user.getId(), "email", user.getEmail()));

        String token = jwtService.generateToken(user);
        return new AuthResponse(token, jwtService.getExpirationMs());
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);
}