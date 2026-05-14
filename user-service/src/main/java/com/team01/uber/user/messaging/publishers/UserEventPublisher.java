package com.team01.uber.user.messaging.publishers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;


@Component
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    private static final String EXCHANGE = "user.events";

    private final RabbitTemplate rabbitTemplate;

    public UserEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Publish user.registered event after successful registration.
     * 
     * @param userId User ID
     * @param email User email
     * @param role User role (ADMIN or RIDER)
     */
    public void publishUserRegistered(Long userId, String email, String role) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId);
            payload.put("email", email);
            payload.put("role", role);
            payload.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(EXCHANGE, "user.registered", payload);
            log.info("Published user.registered for userId={}", userId);
        } catch (Exception e) {
            log.warn("Failed to publish user.registered for userId={}: {}", userId, e.getMessage());
        }
    }

    /**
     * Publish user.deactivated event after successful deactivation.
     * 
     * @param userId User ID
     */
    public void publishUserDeactivated(Long userId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("userId", userId);
            payload.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(EXCHANGE, "user.deactivated", payload);
            log.info("Published user.deactivated for userId={}", userId);
        } catch (Exception e) {
            log.warn("Failed to publish user.deactivated for userId={}: {}", userId, e.getMessage());
        }
    }
}