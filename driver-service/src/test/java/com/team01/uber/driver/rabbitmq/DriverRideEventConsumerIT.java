package com.team01.uber.driver.rabbitmq;

import com.team01.uber.contracts.events.RideCancelledEvent;
import com.team01.uber.contracts.events.RideCompletedEvent;
import com.team01.uber.contracts.events.RidePlacedEvent;
import com.team01.uber.driver.config.DriverEventConfig;
import com.team01.uber.driver.service.DriverService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * §15 Bonus item (2) — Testcontainers RabbitMQ consumer integration test for driver-service.
 *
 * Real RabbitMQ via Testcontainers; DriverService is @MockitoBean-replaced so we
 * focus on consumer dispatch + payload routing. The Elasticsearch-related beans
 * (DriverIndexerService, DriverSearchEsRepository) are mocked AND the four ES
 * Spring Boot auto-configs are excluded — no real ES backend required.
 */
@SpringBootTest(properties = {
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.compatibility-verifier.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:drivertest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
        // String-based auto-config exclusion — avoids Spring Boot 4 package-shuffle
        // brittleness around ES auto-config classes. Anything matching these names
        // in the AutoConfiguration.imports is skipped, no class-import needed.
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration," +
                "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration," +
                "org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchAutoConfiguration," +
                "org.springframework.boot.data.elasticsearch.autoconfigure.DataElasticsearchRepositoriesAutoConfiguration," +
                "org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchClientAutoConfiguration," +
                "org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchRestClientAutoConfiguration"
})
@Testcontainers
class DriverRideEventConsumerIT {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private DriverService driverService;

    // ES-backed collaborators that DriverService normally autowires from the
    // (now-excluded) ES auto-config. Mocked so the context boots cleanly.
    @MockitoBean
    private com.team01.uber.driver.repository.DriverSearchEsRepository driverSearchEsRepository;

    @MockitoBean
    private com.team01.uber.driver.service.DriverIndexerService driverIndexerService;

    @Test
    void ridePlaced_overTheWire_invokesHandleRidePlaced() {
        RidePlacedEvent event = new RidePlacedEvent(81001L, 71001L, 91001L);
        publish("ride.placed", event, "com.team01.uber.contracts.events.RidePlacedEvent");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(driverService, times(1)).handleRidePlaced(eq(91001L), eq(81001L)));
    }

    @Test
    void rideCompleted_overTheWire_invokesHandleRideCompleted() {
        RideCompletedEvent event = new RideCompletedEvent(81002L, 71002L, 91002L, 42.5);
        publish("ride.completed", event, "com.team01.uber.contracts.events.RideCompletedEvent");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(driverService, times(1)).handleRideCompleted(eq(91002L), eq(81002L), eq(42.5)));
    }

    @Test
    void rideCancelled_overTheWire_invokesHandleRideCancelled() {
        RideCancelledEvent event = new RideCancelledEvent(81003L, 71003L, 91003L, "user_requested");
        publish("ride.cancelled", event, "com.team01.uber.contracts.events.RideCancelledEvent");
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(driverService, times(1)).handleRideCancelled(eq(91003L), eq(81003L)));
    }

    private void publish(String routingKey, Object payload, String typeId) {
        rabbitTemplate.convertAndSend(
                DriverEventConfig.RIDE_EVENTS_EXCHANGE,
                routingKey,
                payload,
                msg -> {
                    msg.getMessageProperties().setHeader("__TypeId__", typeId);
                    return msg;
                });
    }
}
