package com.team01.uber.driver.messaging;

import com.team01.uber.contracts.events.SagaStepFailedEvent;
import com.team01.uber.driver.config.DriverEventConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SagaFeedbackPublisher {

    private static final Logger log = LoggerFactory.getLogger(SagaFeedbackPublisher.class);
    private static final String PARTICIPANT = "driver";

    private final RabbitTemplate rabbitTemplate;

    public SagaFeedbackPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * @param originalEvent "ride.completed" or "ride.cancelled"
     * @param suffix        last segment of routing key, e.g. "completed" or "cancelled"
     */
    public void publish(Long rideId, String originalEvent, String suffix,
                        String reason, String detail, String correlationId) {
        SagaStepFailedEvent payload = new SagaStepFailedEvent(
                rideId, PARTICIPANT, originalEvent, reason, detail, correlationId, Instant.now());
        String routingKey = "ride.saga.failed." + PARTICIPANT + "." + suffix;
        try {
            rabbitTemplate.convertAndSend(
                    DriverEventConfig.RIDE_EVENTS_EXCHANGE,
                    routingKey,
                    payload,
                    m -> {
                        if (correlationId != null) {
                            m.getMessageProperties().setHeader("X-Correlation-ID", correlationId);
                        }
                        return m;
                    });
            log.info("Published {} for rideId={} reason={}", routingKey, rideId, reason);
        } catch (Exception pubFailure) {
            log.error("Failed to publish saga-feedback {} for rideId={}: {}", routingKey, rideId,
                    pubFailure.getMessage(), pubFailure);
        }
    }
}
