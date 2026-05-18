package com.team01.uber.ride.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RideEventConfig {

    @Bean
    public Jackson2JsonMessageConverter jsonConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // ── Exchanges ───c─────────────────────────────────────────────────────────

    @Bean
    public TopicExchange rideEventsExchange() {
        return new TopicExchange("ride.events");
    }

    @Bean
    public TopicExchange paymentEventsExchange() {
        return new TopicExchange("payment.events");
    }

    @Bean
    public TopicExchange userEventsExchange() {
        return new TopicExchange("user.events");
    }

    // ── ride.payment.* consumer queues ───────────────────────────────────────

    @Bean
    public Queue ridePaymentInitiatedQueue() {
        return QueueBuilder.durable("ride.payment.initiated")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.payment.initiated.dlq")
                .build();
    }

    @Bean
    public Queue ridePaymentInitiatedDlq() {
        return new Queue("ride.payment.initiated.dlq", true);
    }

    @Bean
    public Binding ridePaymentInitiatedBinding() {
        return BindingBuilder.bind(ridePaymentInitiatedQueue())
                .to(paymentEventsExchange()).with("payment.initiated");
    }

    @Bean
    public Queue ridePaymentCompletedQueue() {
        return QueueBuilder.durable("ride.payment.completed")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.payment.completed.dlq")
                .build();
    }

    @Bean
    public Queue ridePaymentCompletedDlq() {
        return new Queue("ride.payment.completed.dlq", true);
    }

    @Bean
    public Binding ridePaymentCompletedBinding() {
        return BindingBuilder.bind(ridePaymentCompletedQueue())
                .to(paymentEventsExchange()).with("payment.completed");
    }

    @Bean
    public Queue ridePaymentFailedQueue() {
        return QueueBuilder.durable("ride.payment.failed")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.payment.failed.dlq")
                .build();
    }

    @Bean
    public Queue ridePaymentFailedDlq() {
        return new Queue("ride.payment.failed.dlq", true);
    }

    @Bean
    public Binding ridePaymentFailedBinding() {
        return BindingBuilder.bind(ridePaymentFailedQueue())
                .to(paymentEventsExchange()).with("payment.failed");
    }

    @Bean
    public Queue ridePaymentRefundedQueue() {
        return QueueBuilder.durable("ride.payment.refunded")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.payment.refunded.dlq")
                .build();
    }

    @Bean
    public Queue ridePaymentRefundedDlq() {
        return new Queue("ride.payment.refunded.dlq", true);
    }

    @Bean
    public Binding ridePaymentRefundedBinding() {
        return BindingBuilder.bind(ridePaymentRefundedQueue())
                .to(paymentEventsExchange()).with("payment.refunded");
    }

    // ── ride.user.* consumer queues ──────────────────────────────────────────

    @Bean
    public Queue rideUserRegisteredQueue() {
        return QueueBuilder.durable("ride.user.registered")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.user.registered.dlq")
                .build();
    }

    @Bean
    public Queue rideUserRegisteredDlq() {
        return new Queue("ride.user.registered.dlq", true);
    }

    @Bean
    public Binding rideUserRegisteredBinding() {
        return BindingBuilder.bind(rideUserRegisteredQueue())
                .to(userEventsExchange()).with("user.registered");
    }

    @Bean
    public Queue rideUserDeactivatedQueue() {
        return QueueBuilder.durable("ride.user.deactivated")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.user.deactivated.dlq")
                .build();
    }

    @Bean
    public Queue rideUserDeactivatedDlq() {
        return new Queue("ride.user.deactivated.dlq", true);
    }

    @Bean
    public Binding rideUserDeactivatedBinding() {
        return BindingBuilder.bind(rideUserDeactivatedQueue())
                .to(userEventsExchange()).with("user.deactivated");
    }

    // ── ride.saga-feedback queue + DLQ ───────────────────────────────────────

    @Bean
    public Queue rideSagaFeedbackQueue() {
        return QueueBuilder.durable("ride.saga-feedback")
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", "ride.saga-feedback.dlq")
                .build();
    }

    @Bean
    public Queue rideSagaFeedbackDlq() {
        return new Queue("ride.saga-feedback.dlq", true);
    }

    @Bean
    public Binding rideSagaFeedbackBinding() {
        return BindingBuilder.bind(rideSagaFeedbackQueue())
                .to(rideEventsExchange())
                .with("ride.saga.failed.#");
    }
}
