package com.team01.uber.user.rabbitmq;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.user.config.RabbitMQConsumerConfig;
import com.team01.uber.user.model.User;
import com.team01.uber.user.model.UserRideCompletion;
import com.team01.uber.user.model.UserStatus;
import com.team01.uber.user.repository.UserRepository;
import com.team01.uber.user.repository.UserRideCompletionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus item (2) — Testcontainers RabbitMQ consumer integration test for user-service.
 *
 * Real RabbitMQ + real Spring context; UserRepository is @MockitoBean-replaced so the
 * test asserts the repository save was called with the correctly-mutated User
 * (the "mutates the local DB" assertion).
 *
 * Verifies:
 *   1. ride.completed -> userRepository.save called with totalRides+1, totalSpent+fare
 *      AND a UserRideCompletion ledger row written.
 *   2. ride.cancelled -> userRepository.save called with totalRides-1, totalSpent-fare
 *      (fare read from the local UserRideCompletion ledger; the event omits it).
 *   3. user-not-found -> save never called (graceful skip)
 */
@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:usertest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.sql.init.mode=never",
        "spring.data.mongodb.uri=mongodb://localhost:27017/test",
        "feign.user-service.url=http://localhost:1",
        "feign.ride-service.url=http://localhost:1",
        "feign.payment-service.url=http://localhost:1",
        "spring.amqp.deserialization.trust.all=true"
})
@Testcontainers
class UserRideEventConsumerIT {

    @Container
    static RabbitMQContainer rabbit =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @DynamicPropertySource
    static void rabbitProps(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserRideCompletionRepository completionRepository;

    @Test
    void rideCompleted_overTheWire_incrementsUserStats() {
        User u = activeUser(1001L, 4L, 200.0);
        when(userRepository.findById(1001L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(completionRepository.existsById(5001L)).thenReturn(false);

        publish("ride.completed",
                new RideCompletedEvent(5001L, 1001L, 7001L, 50.0),
                "com.team01.uber.contracts.events.RideCompletedEvent");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<UserRideCompletion> recordCaptor = ArgumentCaptor.forClass(UserRideCompletion.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(userRepository, times(1)).save(savedCaptor.capture());
            verify(completionRepository, times(1)).save(recordCaptor.capture());
        });

        User saved = savedCaptor.getValue();
        assertThat(saved.getTotalRides()).isEqualTo(5L);
        assertThat(saved.getTotalSpent()).isEqualTo(250.0);

        UserRideCompletion record = recordCaptor.getValue();
        assertThat(record.getRideId()).isEqualTo(5001L);
        assertThat(record.getUserId()).isEqualTo(1001L);
        assertThat(record.getFare()).isEqualTo(50.0);
    }

    @Test
    void rideCancelled_overTheWire_decrementsUserStats() {
        User u = activeUser(1002L, 3L, 150.0);
        when(userRepository.findById(1002L)).thenReturn(Optional.of(u));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserRideCompletion ledgerRow = new UserRideCompletion();
        ledgerRow.setRideId(5002L);
        ledgerRow.setUserId(1002L);
        ledgerRow.setFare(50.0);
        when(completionRepository.findById(5002L)).thenReturn(Optional.of(ledgerRow));

        publish("ride.cancelled",
                new RideCancelledEvent(5002L, 1002L, 7002L, "user_requested"),
                "com.team01.uber.contracts.events.RideCancelledEvent");

        ArgumentCaptor<User> savedCaptor = ArgumentCaptor.forClass(User.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(userRepository, times(1)).save(savedCaptor.capture());
            verify(completionRepository, times(1)).delete(ledgerRow);
        });

        User saved = savedCaptor.getValue();
        assertThat(saved.getTotalRides()).isEqualTo(2L);
        assertThat(saved.getTotalSpent()).isEqualTo(100.0);
    }

    @Test
    void rideCompleted_userNotFound_saveNeverCalled() {
        when(userRepository.findById(9999L)).thenReturn(Optional.empty());

        publish("ride.completed",
                new RideCompletedEvent(5003L, 9999L, 7003L, 25.0),
                "com.team01.uber.contracts.events.RideCompletedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(userRepository, times(1)).findById(9999L));
        // Scope the never() to userId=9999 to avoid cross-test mock pollution
        // (Spring context + @MockitoBean is shared across @Test methods).
        verify(userRepository, never()).save(argThat(u -> u != null && Long.valueOf(9999L).equals(u.getId())));
    }

    private static User activeUser(Long id, Long totalRides, Double totalSpent) {
        User u = new User();
        u.setId(id);
        u.setStatus(UserStatus.ACTIVE);
        u.setTotalRides(totalRides);
        u.setTotalSpent(totalSpent);
        return u;
    }

    private void publish(String routingKey, Object payload, String typeId) {
        rabbitTemplate.convertAndSend(
                RabbitMQConsumerConfig.RIDE_EVENTS_EXCHANGE,
                routingKey,
                payload,
                msg -> {
                    msg.getMessageProperties().setHeader("__TypeId__", typeId);
                    return msg;
                });
    }
}
