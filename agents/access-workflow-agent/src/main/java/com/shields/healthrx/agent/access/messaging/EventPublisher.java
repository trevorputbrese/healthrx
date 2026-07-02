package com.shields.healthrx.agent.access.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes the agent's events with DETERMINISTIC eventIds (derived from the recommendationId,
 * phase-3-design.md §6) so redeliveries and post-crash re-emits dedupe in the API's
 * processed_events ledger. Throws on failure — callers rely on retry semantics.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final String exchange;
    private final String routingPrefix;
    private final String source;

    public EventPublisher(RabbitTemplate rabbit, ObjectMapper objectMapper,
            @Value("${healthrx.events.exchange}") String exchange,
            @Value("${healthrx.events.routing-prefix}") String routingPrefix,
            @Value("${healthrx.agent.name}") String agentName) {
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.exchange = exchange;
        this.routingPrefix = routingPrefix;
        this.source = "agent:" + agentName;
    }

    public void publish(UUID eventId, String eventType, Instant occurredAt, Map<String, Object> payload) {
        EventEnvelope env = new EventEnvelope(eventId, eventType, occurredAt, source, payload);
        try {
            rabbit.convertAndSend(exchange, routingPrefix + eventType, objectMapper.writeValueAsString(env));
            log.info("published event type={} eventId={}", eventType, eventId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish " + eventType, e);
        }
    }
}
