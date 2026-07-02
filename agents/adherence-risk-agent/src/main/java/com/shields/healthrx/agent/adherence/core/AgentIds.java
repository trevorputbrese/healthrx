package com.shields.healthrx.agent.adherence.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identity spine (phase-3-design.md §6): the same trigger always derives the same
 * recommendationId, and each lifecycle event derives its eventId from that — so broker
 * redeliveries, in-process retries, and post-crash re-runs all dedupe downstream
 * (processed_events for events, agent_tool_calls for tool writes).
 */
public final class AgentIds {

    private AgentIds() {
    }

    public static UUID recommendationId(String agentName, UUID triggerEventId) {
        return name(agentName + "/rec/" + triggerEventId);
    }

    public static UUID createdEventId(UUID recommendationId) {
        return name(recommendationId + "/created");
    }

    public static UUID appliedEventId(UUID recommendationId) {
        return name(recommendationId + "/applied");
    }

    private static UUID name(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
