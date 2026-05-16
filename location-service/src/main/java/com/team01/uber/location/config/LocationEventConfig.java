package com.team01.uber.location.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocationEventConfig {

    public static final String LOCATION_EXCHANGE  = "location.events";
    public static final String ROUTING_KEY_TRACKED = "location.tracked";

    public static final String RIDE_EXCHANGE      = "ride.events";
    public static final String RIDE_SAGA_QUEUE    = "location.ride.saga-listener";
    public static final String RIDE_SAGA_DLQ      = "location.ride.saga-listener.dlq";
    public static final String RIDE_SAGA_DLX      = "location.ride.saga-listener.dlx";

    @Bean
    public TopicExchange locationExchange() {
        return new TopicExchange(LOCATION_EXCHANGE);
    }

    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange(RIDE_EXCHANGE);
    }

    @Bean
    public TopicExchange rideSagaDlx() {
        return new TopicExchange(RIDE_SAGA_DLX);
    }

    @Bean
    public Queue rideSagaDlq() {
        return QueueBuilder.durable(RIDE_SAGA_DLQ).build();
    }

    @Bean
    public Queue rideSagaQueue() {
        return QueueBuilder.durable(RIDE_SAGA_QUEUE)
                .withArgument("x-dead-letter-exchange", RIDE_SAGA_DLX)
                .withArgument("x-dead-letter-routing-key", RIDE_SAGA_DLQ)
                .build();
    }

    @Bean
    public Binding rideSagaPlacedBinding(Queue rideSagaQueue, TopicExchange rideEventsExchange) {
        return BindingBuilder.bind(rideSagaQueue).to(rideEventsExchange).with("ride.placed");
    }

    @Bean
    public Binding rideSagaCompletedBinding(Queue rideSagaQueue, TopicExchange rideEventsExchange) {
        return BindingBuilder.bind(rideSagaQueue).to(rideEventsExchange).with("ride.completed");
    }

    @Bean
    public Binding rideSagaCancelledBinding(Queue rideSagaQueue, TopicExchange rideEventsExchange) {
        return BindingBuilder.bind(rideSagaQueue).to(rideEventsExchange).with("ride.cancelled");
    }

    @Bean
    public Binding dlqBinding(Queue rideSagaDlq, TopicExchange rideSagaDlx) {
        return BindingBuilder.bind(rideSagaDlq).to(rideSagaDlx).with(RIDE_SAGA_DLQ);
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
