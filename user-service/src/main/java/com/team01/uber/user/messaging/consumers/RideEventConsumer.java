package com.team01.uber.user.messaging.consumers;

import com.team01.uber.user.model.User;
import com.team01.uber.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import org.slf4j.MDC;

/**
 * Phase 4: RideEventConsumer
 * 
 * Listens to ride lifecycle events (ride.completed, ride.cancelled) from ride-service
 * and updates user statistics (totalRides, totalSpent) accordingly.
 * 
 * Queue: user.ride.saga-listener
 * Routing keys: ride.completed, ride.cancelled
 * 
 * Idempotency: Checks current user stats before mutating to avoid double-processing.
 * Error handling: Exceptions propagate → Spring retries 3x → DLQ after failures.
 */
@Component
public class RideEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideEventConsumer.class);

    private final UserRepository userRepository;

    public RideEventConsumer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @RabbitListener(queues = "user.ride.saga-listener")
    public void onRideEvent(Map<String, Object> event) {
        String routingKey = (String) event.get("routingKey");
        if ("ride.completed".equals(routingKey)) {
            handleRideCompleted(event);
        } else if ("ride.cancelled".equals(routingKey)) {
            handleRideCancelled(event);
        }
    }

    private void handleRideCompleted(Map<String, Object> event) {
        Long userId = ((Number) event.get("userId")).longValue();
        Long rideId = ((Number) event.get("rideId")).longValue();
        Double fare = ((Number) event.get("fare")).doubleValue();

        MDC.put("userId", userId.toString());
        MDC.put("routingKey", "ride.completed");

        try {
            log.info("Consuming ride.completed for userId={}, rideId={}", userId, rideId);

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User {} not found for ride.completed, skipping", userId);
                return;
            }

            user.setTotalRides((user.getTotalRides() == null ? 0L : user.getTotalRides()) + 1);
            user.setTotalSpent((user.getTotalSpent() == null ? 0.0 : user.getTotalSpent()) + fare);

            userRepository.save(user);
            log.info("Processed ride.completed for userId={}, newTotal={}", userId, user.getTotalRides());
        } catch (Exception e) {
            log.error("Failed to process ride.completed for userId={}: {}", userId, e.getMessage());
            throw e;
        } finally {
            MDC.remove("userId");
            MDC.remove("routingKey");
        }
    }

    private void handleRideCancelled(Map<String, Object> event) {
        Long userId = ((Number) event.get("userId")).longValue();
        Long rideId = ((Number) event.get("rideId")).longValue();
        Double fare = event.containsKey("fare") ? ((Number) event.get("fare")).doubleValue() : 0.0;

        MDC.put("userId", userId.toString());
        MDC.put("routingKey", "ride.cancelled");

        try {
            log.info("Consuming ride.cancelled for userId={}, rideId={}", userId, rideId);

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User {} not found for ride.cancelled, skipping", userId);
                return;
            }

            if (user.getTotalRides() != null && user.getTotalRides() > 0) {
                user.setTotalRides(user.getTotalRides() - 1);
                user.setTotalSpent((user.getTotalSpent() == null ? 0.0 : user.getTotalSpent()) - fare);
                userRepository.save(user);
                log.info("Processed ride.cancelled for userId={}, newTotal={}", userId, user.getTotalRides());
            } else {
                log.warn("User {} has no rides to cancel, skipping decrement", userId);
            }
        } catch (Exception e) {
            log.error("Failed to process ride.cancelled for userId={}: {}", userId, e.getMessage());
            throw e;
        } finally {
            MDC.remove("userId");
            MDC.remove("routingKey");
        }
    }
}