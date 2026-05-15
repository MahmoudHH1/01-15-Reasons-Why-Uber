package com.team01.uber.ride.messaging.consumers;

import com.team01.uber.contracts.events.UserDeactivatedEvent;
import com.team01.uber.contracts.events.UserRegisteredEvent;
import com.team01.uber.ride.model.RideEvent;
import com.team01.uber.ride.repository.RideEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class UserEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserEventConsumer.class);

    private final RideEventRepository rideEventRepository;

    public UserEventConsumer(RideEventRepository rideEventRepository) {
        this.rideEventRepository = rideEventRepository;
    }

    @RabbitListener(queues = "ride.user.registered")
    public void onUserRegistered(UserRegisteredEvent event) {
        try {
            rideEventRepository.save(RideEvent.builder()
                    .action("USER_REGISTERED")
                    .timestamp(LocalDateTime.now())
                    .details(Map.of("userId", event.userId(), "email", event.email(), "role", event.role()))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to audit USER_REGISTERED for userId={}: {}", event.userId(), e.getMessage());
            throw e; // rethrow to trigger retry/dead-lettering
        }
    }

    @RabbitListener(queues = "ride.user.deactivated")
    public void onUserDeactivated(UserDeactivatedEvent event) {
        try {
            rideEventRepository.save(RideEvent.builder()
                    .action("USER_DEACTIVATED")
                    .timestamp(LocalDateTime.now())
                    .details(Map.of("userId", event.userId(), "deactivatedAt", LocalDateTime.now().toString()))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to audit USER_DEACTIVATED for userId={}: {}", event.userId(), e.getMessage());
            throw e; // rethrow to trigger retry/dead-lettering
        }
    }
}
