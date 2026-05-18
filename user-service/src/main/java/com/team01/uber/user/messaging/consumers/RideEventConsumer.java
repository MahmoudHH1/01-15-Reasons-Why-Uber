package com.team01.uber.user.messaging.consumers;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserRideCompletion;
import com.team01.uber.user.repository.UserRepository;
import com.team01.uber.user.repository.UserRideCompletionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/*
 * Spec: M3 §15.2 (consumer ITs) + §16 rule 11 (idempotent consumers) + §8 saga.
 * Quote (uber-m3.md §2): "Event payload records cross the wire as JSON
 * (Jackson2-based converter on both publisher and consumer sides)."
 *
 * Why: prior to fix this class declared TWO @RabbitListener methods on the
 * same queue (user.ride.saga-listener), each filtering by a payload field
 * and silently returning when it didn't match. Spring AMQP creates one
 * SimpleMessageListenerContainer per @RabbitListener, so two consumers
 * raced for each message; RabbitMQ round-robined ~50% of events into the
 * "wrong" handler that just ack'd and dropped them. User.totalRides /
 * totalSpent updates landed only half the time.
 *
 * Fix: one class-level @RabbitListener (single consumer on the queue) +
 * @RabbitHandler methods dispatched in-process by deserialized record
 * type (RideCompletedEvent vs RideCancelledEvent). Same shape as
 * payment-service/PaymentEventConsumer.
 */
@Component
@RabbitListener(queues = "user.ride.saga-listener")
public class RideEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RideEventConsumer.class);

    private final UserRepository userRepository;
    private final UserRideCompletionRepository completionRepository;

    @Autowired
    private CacheManager cacheManager;

    public RideEventConsumer(UserRepository userRepository,
                             UserRideCompletionRepository completionRepository) {
        this.userRepository = userRepository;
        this.completionRepository = completionRepository;
    }

    @RabbitHandler
    public void onRideCompleted(RideCompletedEvent event) {
        Long userId = event.userId();
        Long rideId = event.rideId();
        Double fare = event.fare() != null ? event.fare() : 0.0;

        MDC.put("userId", userId.toString());
        MDC.put("routingKey", "ride.completed");

        try {
            log.info("Consuming ride.completed for userId={}, rideId={}", userId, rideId);

            if (completionRepository.existsById(rideId)) {
                log.info("ride.completed for rideId={} already processed, skipping (idempotent)", rideId);
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User {} not found for ride.completed, skipping", userId);
                return;
            }

            user.setTotalRides((user.getTotalRides() == null ? 0L : user.getTotalRides()) + 1);
            user.setTotalSpent((user.getTotalSpent() == null ? 0.0 : user.getTotalSpent()) + fare);
            userRepository.save(user);

            UserRideCompletion record = new UserRideCompletion();
            record.setRideId(rideId);
            record.setUserId(userId);
            record.setFare(fare);
            record.setCompletedAt(LocalDateTime.now());
            completionRepository.save(record);

            cacheManager.getCache("user-service::S1-F1").evict(userId);

            log.info("Processed ride.completed for userId={}, newTotal={}, newSpent={}",
                    userId, user.getTotalRides(), user.getTotalSpent());
        } catch (Exception e) {
            log.error("Failed to process ride.completed for userId={}: {}", userId, e.getMessage());
            throw e;
        } finally {
            MDC.remove("userId");
            MDC.remove("routingKey");
        }
    }

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event) {
        Long userId = event.userId();
        Long rideId = event.rideId();

        MDC.put("userId", userId.toString());
        MDC.put("routingKey", "ride.cancelled");

        try {
            log.info("Consuming ride.cancelled for userId={}, rideId={}", userId, rideId);

            UserRideCompletion record = completionRepository.findById(rideId).orElse(null);
            if (record == null) {
                log.info("No completion record for rideId={} — already reversed or never seen (idempotent)", rideId);
                return;
            }

            User user = userRepository.findById(userId).orElse(null);
            if (user == null) {
                log.warn("User {} not found for ride.cancelled, skipping", userId);
                completionRepository.delete(record);
                return;
            }

            long currentRides = user.getTotalRides() == null ? 0L : user.getTotalRides();
            user.setTotalRides(Math.max(0L, currentRides - 1));

            double currentSpent = user.getTotalSpent() == null ? 0.0 : user.getTotalSpent();
            user.setTotalSpent(Math.max(0.0, currentSpent - record.getFare()));

            userRepository.save(user);
            completionRepository.delete(record);

            cacheManager.getCache("user-service::S1-F1").evict(userId);

            log.info("Processed ride.cancelled for userId={}, newTotal={}, newSpent={}",
                    userId, user.getTotalRides(), user.getTotalSpent());
        } catch (Exception e) {
            log.error("Failed to process ride.cancelled for userId={}: {}", userId, e.getMessage());
            throw e;
        } finally {
            MDC.remove("userId");
            MDC.remove("routingKey");
        }
    }
}
