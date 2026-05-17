package com.team01.uber.payment.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentEventConfig {

    public static final String PAYMENT_EVENTS_EXCHANGE = "payment.events";
    public static final String RIDE_EVENTS_EXCHANGE    = "ride.events";
    public static final String PAYMENT_DLX             = "payment.dlx";

    public static final String SAGA_LISTENER_QUEUE = "payment.saga-listener";
    public static final String SAGA_LISTENER_DLQ   = "payment.saga-listener.dlq";

    public static final String ROUTING_RIDE_COMPLETED = "ride.completed";
    public static final String ROUTING_RIDE_CANCELLED = "ride.cancelled";
    public static final String ROUTING_PAYMENT_INITIATED  = "payment.initiated";
    public static final String ROUTING_PAYMENT_COMPLETED  = "payment.completed";
    public static final String ROUTING_PAYMENT_FAILED     = "payment.failed";
    public static final String ROUTING_PAYMENT_REFUNDED   = "payment.refunded";

    @Bean
    public TopicExchange paymentEventsExchange() {
        return new TopicExchange(PAYMENT_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange(RIDE_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange paymentDlxExchange() {
        return new DirectExchange(PAYMENT_DLX, true, false);
    }

    @Bean
    public Queue paymentSagaListenerQueue() {
        return QueueBuilder.durable(SAGA_LISTENER_QUEUE)
                .withArgument("x-dead-letter-exchange", PAYMENT_DLX)
                .withArgument("x-dead-letter-routing-key", SAGA_LISTENER_DLQ)
                .build();
    }

    @Bean
    public Queue paymentSagaListenerDlq() {
        return QueueBuilder.durable(SAGA_LISTENER_DLQ).build();
    }

    @Bean
    public Binding sagaListenerBindingRideCompleted() {
        return BindingBuilder.bind(paymentSagaListenerQueue())
                .to(rideEventsExchange())
                .with(ROUTING_RIDE_COMPLETED);
    }

    @Bean
    public Binding sagaListenerBindingRideCancelled() {
        return BindingBuilder.bind(paymentSagaListenerQueue())
                .to(rideEventsExchange())
                .with(ROUTING_RIDE_CANCELLED);
    }

    @Bean
    public Binding sagaListenerDlqBinding() {
        return BindingBuilder.bind(paymentSagaListenerDlq())
                .to(paymentDlxExchange())
                .with(SAGA_LISTENER_DLQ);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
