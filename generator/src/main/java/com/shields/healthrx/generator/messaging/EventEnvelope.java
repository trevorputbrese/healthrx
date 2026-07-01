package com.shields.healthrx.generator.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Wire contract shared with the HealthRx API consumer (kept in sync via phase-2-design.md). */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String source,
        Map<String, Object> payload) {
}
