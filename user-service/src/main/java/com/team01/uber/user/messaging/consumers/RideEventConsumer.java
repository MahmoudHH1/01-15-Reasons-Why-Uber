package com.team01.uber.user.messaging.consumers;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.user.model.User;
import com.team01.uber.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

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

    public RideEventConsumer(UserRepository userRepository) {
        this.userRepository = userRepository;
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

    @RabbitHandler
    public void onRideCancelled(RideCancelledEvent event) {
        Long userId = event.userId();
        Long rideId = event.rideId();

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
