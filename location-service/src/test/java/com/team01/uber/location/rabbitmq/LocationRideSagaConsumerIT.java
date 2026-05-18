package com.team01.uber.location.rabbitmq;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.location.config.LocationEventConfig;
import com.team01.uber.location.model.LocationTrackingEvent;
import com.team01.uber.location.model.LocationTrackingEventKey;
import com.team01.uber.location.observer.EntityObserver;
import com.team01.uber.location.repository.LocationTrackingEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §15 Bonus / §8.6 — RabbitMQ consumer integration test with Testcontainers.
 *
 * Spins up a real RabbitMQ broker via Testcontainers and the location-service
 * Spring context wired against it. The Cassandra/Mongo/Postgres dependencies
 * are @MockBean'd so the test stays focused on the consumer dispatch and
 * downstream state-mutation behavior.
 *
 * Verifies:
 *   1. ride.placed publish  -> LocationRideSagaConsumer.onRidePlaced fires;
 *      Observer (MongoEventLogger) is notified with action=LOCATION_UPDATED.
 *   2. ride.completed publish -> onRideCompleted fires, findTopByKeyDriverId
 *      is queried, and trackingRepository.save(...) is called with rideId set
 *      on the row (proves §6 "mark the most recent location for this driver
 *      with the rideId" behavior at the wire level).
 *   3. ride.cancelled publish -> onRideCancelled fires, no Cassandra mutation
 *      (per §6: "no Cassandra mutation"), Observer is notified.
 */
@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.cassandra.schema-action=none",
        "spring.data.mongodb.uri=mongodb://localhost:27017/test",
        "feign.driver-service.url=http://localhost:1",
        "feign.user-service.url=http://localhost:1"
})
@Testcontainers
class LocationRideSagaConsumerIT {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    @SuppressWarnings("resource")
    static CassandraContainer<?> cassandra =
            new CassandraContainer<>(DockerImageName.parse("cassandra:4.1"))
                    .withStartupTimeout(Duration.ofMinutes(5));

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // CassandraConfig (AbstractCassandraConfiguration) reads the legacy
        // spring.data.cassandra.* names, not Spring Boot 4's spring.cassandra.*.
        registry.add("spring.data.cassandra.contact-points", cassandra::getHost);
        registry.add("spring.data.cassandra.port", () -> cassandra.getMappedPort(9042));
        registry.add("spring.data.cassandra.local-datacenter", cassandra::getLocalDatacenter);
        registry.add("spring.data.cassandra.keyspace-name", () -> "uberks");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private LocationTrackingEventRepository trackingRepository;

    @MockitoBean(name = "mongoEventLogger")
    private EntityObserver mongoEventLogger;

    @Test
    void ridePlaced_consumerFires_andNotifiesObserverWithLocationUpdated() {
        RidePlacedEvent event = new RidePlacedEvent(9001L, 7001L, 8001L);

        publish("ride.placed", event, "com.team01.uber.contracts.events.RidePlacedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(mongoEventLogger, times(1))
                        .onEvent(eq("LOCATION_UPDATED"), any()));
        verify(trackingRepository, never()).save(any());
    }

    @Test
    void ridePlaced_redelivered_redisIdempotency_secondDeliveryIsSkipped() {
        RidePlacedEvent event = new RidePlacedEvent(9006L, 7006L, 8006L);

        publish("ride.placed", event, "com.team01.uber.contracts.events.RidePlacedEvent");
        publish("ride.placed", event, "com.team01.uber.contracts.events.RidePlacedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(mongoEventLogger, times(1))
                        .onEvent(eq("LOCATION_UPDATED"), any()));
    }

    @Test
    void rideCompleted_marksLatestTrackingRow_withRideId_perSection6() {
        LocationTrackingEvent latest = new LocationTrackingEvent();
        latest.setKey(new LocationTrackingEventKey(8002L, Instant.now()));
        latest.setLatitude(30.0);
        latest.setLongitude(31.0);
        when(trackingRepository.findTopByKeyDriverId(8002L)).thenReturn(Optional.of(latest));

        RideCompletedEvent event = new RideCompletedEvent(9002L, 7002L, 8002L, 42.5);
        publish("ride.completed", event, "com.team01.uber.contracts.events.RideCompletedEvent");

        ArgumentCaptor<LocationTrackingEvent> saveCaptor = ArgumentCaptor.forClass(LocationTrackingEvent.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(trackingRepository, times(1)).findTopByKeyDriverId(8002L);
            verify(trackingRepository, times(1)).save(saveCaptor.capture());
            verify(mongoEventLogger, times(1)).onEvent(eq("TRIP_COMPLETED"), any());
        });

        LocationTrackingEvent saved = saveCaptor.getValue();
        assertThat(saved.getRideId())
                .as("§6: 'Mark the most recent location for this driver with the rideId' — must be the exact rideId from the event")
                .isEqualTo(9002L);
        assertThat(saved.getKey().getDriverId())
                .as("the row mutated must be the LATEST row for the driver from the event")
                .isEqualTo(8002L);
    }

    @Test
    void rideCompleted_redelivered_isIdempotent_secondDeliveryIsNoOp() {
        LocationTrackingEvent latest = new LocationTrackingEvent();
        latest.setKey(new LocationTrackingEventKey(8004L, Instant.now()));
        latest.setLatitude(30.0);
        latest.setLongitude(31.0);
        when(trackingRepository.findTopByKeyDriverId(8004L)).thenReturn(Optional.of(latest));

        RideCompletedEvent event = new RideCompletedEvent(9004L, 7004L, 8004L, 42.5);
        publish("ride.completed", event, "com.team01.uber.contracts.events.RideCompletedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(trackingRepository, times(1)).save(any(LocationTrackingEvent.class));
            verify(mongoEventLogger, times(1)).onEvent(eq("TRIP_COMPLETED"), any());
        });

        publish("ride.completed", event, "com.team01.uber.contracts.events.RideCompletedEvent");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(trackingRepository, times(2)).findTopByKeyDriverId(8004L));
        verify(trackingRepository, times(1)).save(any(LocationTrackingEvent.class));
        verify(mongoEventLogger, times(1)).onEvent(eq("TRIP_COMPLETED"), any());
    }

    @Test
    void rideCompleted_noPriorPing_noSave_butObserverStillNotified() {
        when(trackingRepository.findTopByKeyDriverId(8005L)).thenReturn(Optional.empty());

        RideCompletedEvent event = new RideCompletedEvent(9005L, 7005L, 8005L, 42.5);
        publish("ride.completed", event, "com.team01.uber.contracts.events.RideCompletedEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(trackingRepository, times(1)).findTopByKeyDriverId(8005L);
            verify(mongoEventLogger, times(1)).onEvent(eq("TRIP_COMPLETED"), any());
        });
        verify(trackingRepository, never()).save(any());
    }

    @Test
    void rideCancelled_writesTripCancelled_withoutCassandraMutation_perSection6() {
        RideCancelledEvent event = new RideCancelledEvent(9003L, 7003L, 8003L, "DRIVER_NO_SHOW");

        publish("ride.cancelled", event, "com.team01.uber.contracts.events.RideCancelledEvent");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(mongoEventLogger, times(1))
                        .onEvent(eq("TRIP_CANCELLED"), any()));
        // §6: "no Cassandra mutation"
        verify(trackingRepository, never()).save(any());
        verify(trackingRepository, never()).findTopByKeyDriverId(any());
    }

    private void publish(String routingKey, Object payload, String typeId) {
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setHeader("__TypeId__", typeId);
        rabbitTemplate.convertAndSend(
                LocationEventConfig.RIDE_EXCHANGE,
                routingKey,
                payload,
                msg -> {
                    msg.getMessageProperties().setHeader("__TypeId__", typeId);
                    return msg;
                });
    }
}
