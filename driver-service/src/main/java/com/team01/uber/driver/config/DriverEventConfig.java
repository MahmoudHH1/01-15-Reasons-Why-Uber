package com.team01.uber.driver.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DriverEventConfig {

    public static final String DRIVER_EVENTS_EXCHANGE = "driver.events";
    public static final String RIDE_EVENTS_EXCHANGE = "ride.events";

    public static final String RIDE_SAGA_QUEUE = "driver.ride.saga-listener";
    public static final String RIDE_SAGA_DLX = "driver.ride.saga-listener.dlx";
    public static final String RIDE_SAGA_DLQ = "driver.ride.saga-listener.dlq";

    public static final String ROUTING_DRIVER_STATUS_CHANGED = "driver.status-changed";
    public static final String ROUTING_DRIVER_RATED = "driver.rated";
    public static final String ROUTING_DRIVER_DOCUMENT_VERIFIED = "driver.document.verified";

    public static final String ROUTING_RIDE_PLACED = "ride.placed";
    public static final String ROUTING_RIDE_COMPLETED = "ride.completed";
    public static final String ROUTING_RIDE_CANCELLED = "ride.cancelled";

    @Bean
    TopicExchange driverEventsExchange() {
        return new TopicExchange(DRIVER_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange rideEventsExchange() {
        return new TopicExchange(RIDE_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange rideSagaDlx() {
        return new DirectExchange(RIDE_SAGA_DLX, true, false);
    }

    @Bean
    Queue rideSagaDlq() {
        return QueueBuilder.durable(RIDE_SAGA_DLQ).build();
    }

    @Bean
    Binding rideSagaDlqBinding(Queue rideSagaDlq, DirectExchange rideSagaDlx) {
        return BindingBuilder.bind(rideSagaDlq).to(rideSagaDlx).with(RIDE_SAGA_DLQ);
    }

    @Bean
    Queue rideSagaQueue() {
        return QueueBuilder.durable(RIDE_SAGA_QUEUE)
                .withArgument("x-dead-letter-exchange", RIDE_SAGA_DLX)
                .withArgument("x-dead-letter-routing-key", RIDE_SAGA_DLQ)
                .build();
    }

    @Bean
    Binding rideSagaPlacedBinding(Queue rideSagaQueue, TopicExchange rideEventsExchange) {
        return BindingBuilder.bind(rideSagaQueue).to(rideEventsExchange).with(ROUTING_RIDE_PLACED);
    }

    @Bean
    Binding rideSagaCompletedBinding(Queue rideSagaQueue, TopicExchange rideEventsExchange) {
        return BindingBuilder.bind(rideSagaQueue).to(rideEventsExchange).with(ROUTING_RIDE_COMPLETED);
    }

    @Bean
    Binding rideSagaCancelledBinding(Queue rideSagaQueue, TopicExchange rideEventsExchange) {
        return BindingBuilder.bind(rideSagaQueue).to(rideEventsExchange).with(ROUTING_RIDE_CANCELLED);
    }

    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
