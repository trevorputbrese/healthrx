package com.shields.healthrx.agent.financial.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identity spine: the recommendation id derives from the triggering event, so
 * redeliveries and replays dedupe rather than minting a second recommendation for the same case.
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

    private static UUID name(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
