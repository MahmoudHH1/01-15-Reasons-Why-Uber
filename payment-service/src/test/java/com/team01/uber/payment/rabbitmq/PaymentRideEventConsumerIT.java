package com.team01.uber.payment.rabbitmq;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.payment.config.PaymentEventConfig;
import com.team01.uber.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * §15 Bonus item (2) — Testcontainers RabbitMQ consumer integration test for payment-service.
 *
 * Real RabbitMQ + real Spring context; PaymentService is @MockitoBean-replaced so the
 * test focuses on consumer dispatch + payload routing without depending on the live
 * Mongo / Postgres / Redis stack.
 *
 * Verifies:
 *   1. ride.completed -> PaymentService.processRideCompleted invoked with the event
 *      (payment-service kicks off the saga payment by creating a PENDING payment).
 *   2. ride.cancelled -> PaymentService.processRideCancelled invoked with the event
 *      (compensation branch: refund any pre-existing payment).
 */
@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:paymenttest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.sql.init.mode=never",
        "spring.data.mongodb.uri=mongodb://localhost:27017/test",
        "spring.data.redis.host=localhost",
        "feign.user-service.url=http://localhost:1",
        "feign.ride-service.url=http://localhost:1",
        "feign.driver-service.url=http://localhost:1"
})
@Testcontainers
class PaymentRideEventConsumerIT {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void rideCompleted_overTheWire_invokesProcessRideCompletedWithMatchingPayload() {
        RideCompletedEvent event = new RideCompletedEvent(91001L, 71001L, 81001L, 42.5);

        publish(PaymentEventConfig.ROUTING_RIDE_COMPLETED, event,
                "com.team01.uber.contracts.events.RideCompletedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(paymentService, times(1)).processRideCompleted(argThat(e ->
                        e.rideId().equals(91001L) &&
                        e.userId().equals(71001L) &&
                        e.driverId().equals(81001L) &&
                        e.fare().equals(42.5))));
    }

    @Test
    void rideCancelled_overTheWire_invokesProcessRideCancelledWithMatchingPayload() {
        RideCancelledEvent event = new RideCancelledEvent(91002L, 71002L, 81002L, "user_requested");

        publish(PaymentEventConfig.ROUTING_RIDE_CANCELLED, event,
                "com.team01.uber.contracts.events.RideCancelledEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(paymentService, times(1)).processRideCancelled(argThat(e ->
                        e.rideId().equals(91002L) &&
                        "user_requested".equals(e.reason()))));
    }

    @Test
    void rideCompleted_andRideCancelled_routeIndependently_notDispatchedToOtherMethod() {
        publish(PaymentEventConfig.ROUTING_RIDE_COMPLETED,
                new RideCompletedEvent(91003L, 71003L, 81003L, 30.0),
                "com.team01.uber.contracts.events.RideCompletedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(paymentService, times(1)).processRideCompleted(any()));
        verify(paymentService, times(0)).processRideCancelled(any());
    }

    private void publish(String routingKey, Object payload, String typeId) {
        rabbitTemplate.convertAndSend(
                PaymentEventConfig.RIDE_EVENTS_EXCHANGE,
                routingKey,
                payload,
                msg -> {
                    msg.getMessageProperties().setHeader("__TypeId__", typeId);
                    return msg;
                });
    }
}
