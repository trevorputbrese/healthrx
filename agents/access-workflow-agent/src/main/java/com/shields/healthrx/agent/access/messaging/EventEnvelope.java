package com.shields.healthrx.agent.access.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Wire contract for events on {@code healthrx.events}. See phase-2-design.md. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String source,
        Map<String, Object> payload) {
}
