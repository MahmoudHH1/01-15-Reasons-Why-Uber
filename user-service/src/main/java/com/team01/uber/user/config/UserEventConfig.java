package com.team01.uber.user.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 1.1: UserEventConfig
 * 
 * Declares the RabbitMQ TopicExchange for user domain events.
 * This service acts as a PRODUCER for user lifecycle events:
 *   - user.registered  (routing key: "user.registered")
 *   - user.deactivated (routing key: "user.deactivated")
 * 
 * Other services (ride-service, location-service, etc.) will declare their own
 * queues and bind to this exchange if they want to listen for user events.
 * 
 * Note: We do NOT declare queues here. Producer declares only the exchange.
 *       Consumer queues are declared in RabbitMQConsumerConfig (Phase 1.3).
 */
@Configuration
public class UserEventConfig {

    /**
     * User events TopicExchange
     * 
     * Exchange name: "user.events"
     * Type: topic (allows routing based on routing keys like "user.*")
     * Durable: true (survives broker restart)
     * AutoDelete: false (not auto-deleted when last queue is unbound)
     * 
     * Routing keys used:
     *   - "user.registered"   → published after M1-F10 registration succeeds
     *   - "user.deactivated"  → published after S1-F4 deactivation succeeds
     */
    @Bean
    public TopicExchange userEventsExchange() {
        return new TopicExchange(
            "user.events",  // exchange name
            true,           // durable
            false           // autoDelete
        );
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}