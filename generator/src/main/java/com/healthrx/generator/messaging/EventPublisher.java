package com.healthrx.generator.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Publishes workflow events as JSON to the topic exchange with routing key {@code <prefix><EventType>}. */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String routingPrefix;

    public EventPublisher(RabbitTemplate rabbit, ObjectMapper objectMapper,
            @Value("${healthrx.events.exchange}") String exchange,
            @Value("${healthrx.events.routing-prefix}") String routingPrefix) {
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.routingPrefix = routingPrefix;
    }

    public void publish(String eventType, Instant occurredAt, Map<String, Object> payload) {
        EventEnvelope env = new EventEnvelope(UUID.randomUUID(), eventType, occurredAt, "generator", payload);
        try {
            rabbit.convertAndSend(exchange, routingPrefix + eventType, objectMapper.writeValueAsString(env));
            log.info("published event type={} routingKey={} payload={}", eventType, routingPrefix + eventType, payload);
        } catch (Exception e) {
            log.warn("Failed to publish event {}", eventType, e);
        }
    }
}
