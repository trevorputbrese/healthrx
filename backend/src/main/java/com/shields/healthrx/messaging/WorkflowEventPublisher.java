package com.shields.healthrx.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.domain.WorkflowEventType;

/**
 * Publishes select workflow events back onto the {@code healthrx.events} topic exchange so the
 * agent apps can sense human-driven workflow moments (today: a PA submission advanced from the
 * UI, which the Access Workflow Agent follows up with the payer autonomously).
 *
 * <p>Publishing is deferred to after-commit (the consumer must see the committed row to no-op
 * idempotently) and is best-effort: a broker blip degrades agent sensing, never the user's write.
 */
@Component
public class WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventPublisher.class);
    private static final String SOURCE = "healthrx-api";

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final String exchange;
    private final String routingPrefix;

    /** RabbitTemplate is optional so brokerless test slices (Rabbit autoconfig excluded) still boot. */
    public WorkflowEventPublisher(ObjectProvider<RabbitTemplate> rabbit, ObjectMapper mapper,
            @Value("${healthrx.events.exchange}") String exchange,
            @Value("${healthrx.events.routing-prefix}") String routingPrefix) {
        this.rabbit = rabbit.getIfAvailable();
        this.mapper = mapper;
        this.exchange = exchange;
        this.routingPrefix = routingPrefix;
    }

    public String source() {
        return SOURCE;
    }

    /**
     * Publishes after the surrounding transaction commits (or immediately if none is active).
     * The caller supplies the eventId so it can pre-claim it in {@code processed_events} within
     * the same transaction — the API's own consumer then no-ops instead of re-applying (or, on
     * the resubmit path, regressing) the event it just published.
     */
    public void publishAfterCommit(UUID eventId, WorkflowEventType type, Instant occurredAt,
            Map<String, Object> payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(eventId, type, occurredAt, payload);
                }
            });
        } else {
            publish(eventId, type, occurredAt, payload);
        }
    }

    private void publish(UUID eventId, WorkflowEventType type, Instant occurredAt, Map<String, Object> payload) {
        if (rabbit == null) {
            log.debug("No RabbitTemplate available — skipping publish of {}", type.wireName());
            return;
        }
        EventEnvelope envelope = new EventEnvelope(eventId, type.wireName(), occurredAt, SOURCE, payload);
        try {
            rabbit.convertAndSend(exchange, routingPrefix + type.wireName(),
                    mapper.writeValueAsString(envelope));
            log.info("workflow_event_published type={} id={}", type.wireName(), envelope.eventId());
        } catch (Exception e) {
            log.warn("Best-effort event publish failed type={} — agents will not sense this change",
                    type.wireName(), e);
        }
    }
}
