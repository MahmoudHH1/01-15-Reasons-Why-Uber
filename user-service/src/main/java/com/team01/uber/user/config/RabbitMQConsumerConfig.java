package com.team01.uber.user.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/**
 * RabbitMQ consumer topology for user-service.
 * Declares the queue that listens to ride.completed and ride.cancelled events,
 * plus the dead-letter queue for failed messages.
 *
 * Producer (ride-service) declares ride.events TopicExchange.
 * This class declares the consumer-side: queue + DLQ + binding.
 *
 * Topology:
 *   ride.events (TopicExchange, declared by ride-service)
 *       ↓ binding: routing key "ride.*"
 *   user.ride.saga-listener (Queue)
 *       ↓ x-dead-letter-exchange argument
 *   user.ride.saga-listener.dlq.exchange (TopicExchange, DLX)
 *       ↓ binding: routing key "#"
 *   user.ride.saga-listener.dlq (Queue, DLQ)
 *
 * When a message arrives on ride.completed or ride.cancelled:
 * 1. Consumer listener processes the message
 * 2. If listener throws exception:
 *    - Spring auto-retries (max 3 times) due to application.yml config
 *    - After 3rd retry still fails: message rejected
 *    - default-requeue-rejected: false sends to DLX
 *    - DLX routes to DLQ with x-dead-letter-routing-key header
 * 3. Message sits in DLQ for manual inspection/replay
 *
 * No manual basicAck/basicNack—Spring handles all ACK logic via acknowledge-mode: auto.
 */
@Configuration
public class RabbitMQConsumerConfig {

    // Queue and exchange names (constants for reuse in listeners)
    public static final String RIDE_SAGA_LISTENER_QUEUE = "user.ride.saga-listener";
    public static final String RIDE_SAGA_LISTENER_DLQ = "user.ride.saga-listener.dlq";
    public static final String RIDE_SAGA_LISTENER_DLX = "user.ride.saga-listener.dlq.exchange";
    public static final String RIDE_EVENTS_EXCHANGE = "ride.events";


    @Bean
    public CachingConnectionFactory connectionFactory() {
        CachingConnectionFactory factory = new CachingConnectionFactory();
        factory.setHost("rabbitmq");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");
        return factory;
    }

    /**
     * Consumer queue for ride lifecycle events (ride.completed, ride.cancelled).
     *
     * Arguments:
     *   x-dead-letter-exchange: the exchange to send failed messages to (our DLX)
     *   x-dead-letter-routing-key: the routing key for DLX (dlq.* or # both work)
     */
    @Bean
    public Queue rideEventSagaListenerQueue() {
        return QueueBuilder.durable(RIDE_SAGA_LISTENER_QUEUE)
            .withArgument("x-dead-letter-exchange", RIDE_SAGA_LISTENER_DLX)
            .withArgument("x-dead-letter-routing-key", "dlq.*")
            .build();
    }

    /**
     * Dead-letter queue for messages that fail after max retries.
     *
     * Receives messages from user.ride.saga-listener when processing fails.
     * Operator can inspect, troubleshoot, and manually replay via management UI.
     */
    @Bean
    public Queue rideEventSagaListenerDLQ() {
        return QueueBuilder.durable(RIDE_SAGA_LISTENER_DLQ).build();
    }

    /**
     * Topic exchange that ride.events publishes to.
     *
     * Declared by ride-service (S3-EVENTS), but we reference it here for the binding.
     * Spring deduplicates multiple TopicExchange beans with the same name—only one
     * actual RabbitMQ exchange is created, even though both ride-service and user-service
     * declare beans for "ride.events".
     *
     * Args: name, durable=true, autoDelete=false
     */
    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange(RIDE_EVENTS_EXCHANGE, true, false);
    }

    /**
     * Dead-letter exchange that routes failed messages from the consumer queue.
     *
     * When x-dead-letter-exchange is set on a queue and a message is rejected,
     * RabbitMQ sends it to this exchange with the x-dead-letter-routing-key header.
     */
    @Bean
    public TopicExchange rideEventsDLX() {
        return new TopicExchange(RIDE_SAGA_LISTENER_DLX, true, false);
    }

    /**
     * Binding: consumer queue listens to "ride.*" events from ride.events exchange.
     *
     * Routing key "ride.*" matches:
     *   ✓ ride.completed
     *   ✓ ride.cancelled
     *   ✗ ride.completed.extra (no—wildcards only match one level)
     *   ✓ ride.anything (matches any single-word suffix)
     *
     * If in the future S3 publishes "ride.paused" or "ride.updated", this binding
     * automatically picks them up without code changes (benefit of TopicExchange over Direct).
     */
    @Bean
    public Binding rideEventsBinding() {
        return BindingBuilder.bind(rideEventSagaListenerQueue())
            .to(rideEventsExchange())
            .with("ride.*");
    }

    /**
     * Binding: DLQ listens to all failed messages from the DLX.
     *
     * Routing key "#" (match-all) ensures that any message sent to the DLX
     * ends up in the DLQ, regardless of its original routing key.
     *
     * Example: a message that arrived on "ride.completed" fails after 3 retries,
     * is sent to DLX with routing key "ride.completed", and the "#" binding
     * catches it and puts it in the DLQ.
     */
    @Bean
    public Binding rideEventsDLQBinding() {
        return BindingBuilder.bind(rideEventSagaListenerDLQ())
            .to(rideEventsDLX())
            .with("#");
    }
}