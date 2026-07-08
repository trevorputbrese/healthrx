package com.healthrx.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import java.time.format.DateTimeParseException;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrx.repo.ProcessedEventRepository;
import com.healthrx.service.EventApplicationService;
import com.healthrx.web.ApiException;

/**
 * Consumes events from {@code healthrx.events.api}. Idempotent (skips already-processed eventIds),
 * dead-letters unparseable / non-applicable events instead of crashing or looping.
 */
@Component
@ConditionalOnProperty(name = "healthrx.events.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processed;
    private final EventApplicationService application;

    public EventConsumer(ObjectMapper objectMapper, ProcessedEventRepository processed,
            EventApplicationService application) {
        this.objectMapper = objectMapper;
        this.processed = processed;
        this.application = application;
    }

    @RabbitListener(queues = "${healthrx.events.queue}")
    public void onMessage(Message message) {
        EventEnvelope env;
        try {
            env = objectMapper.readValue(message.getBody(), EventEnvelope.class);
        } catch (Exception e) {
            log.warn("Discarding unparseable event message", e);
            throw new AmqpRejectAndDontRequeueException("Unparseable event envelope", e);
        }
        if (env.eventId() == null || env.eventType() == null) {
            throw new AmqpRejectAndDontRequeueException("Event missing eventId/eventType");
        }
        if (processed.isProcessed(env.eventId())) {
            log.debug("Skipping already-processed event {} ({})", env.eventId(), env.eventType());
            return;
        }
        try {
            application.apply(env);
            log.info("workflow_event_applied type={} id={} source={}",
                    env.eventType(), env.eventId(), env.source());
        } catch (ApiException | IllegalArgumentException | DateTimeParseException
                | DataIntegrityViolationException e) {
            // Deterministic bad/non-applicable event: dead-letter immediately (no retry).
            log.warn("Dead-lettering bad/non-applicable event type={} id={} error={}",
                    env.eventType(), env.eventId(), e.toString());
            throw new AmqpRejectAndDontRequeueException("Bad or non-applicable event", e);
        }
        // Other (transient) exceptions propagate -> retried per listener config -> then dead-lettered.
    }
}
