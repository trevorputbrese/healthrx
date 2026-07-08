package com.healthrx.agent.access.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identity spine (phase-3-design.md §6). Event-triggered runs derive from the
 * trigger eventId; scan-detected runs have no broker event, so they derive from the stuck
 * *episode* — (referral, rule, when it entered the stuck state, and how many prior decided
 * recommendations exist for it) — so scan repeats and post-crash re-runs of the same episode
 * dedupe, while a genuinely new recommendation after a dismissal derives a new id.
 */
public final class AgentIds {

    private AgentIds() {
    }

    public static UUID recommendationId(String agentName, UUID triggerEventId) {
        return name(agentName + "/rec/" + triggerEventId);
    }

    /** Synthesized trigger id for a scan-detected stuck episode. */
    public static UUID scanEpisodeId(String agentName, UUID referralId, String rule,
            String statusEnteredAt, long priorDecidedCount) {
        return name(agentName + "/episode/" + referralId + "/" + rule + "/" + statusEnteredAt
                + "/" + priorDecidedCount);
    }

    public static UUID createdEventId(UUID recommendationId) {
        return name(recommendationId + "/created");
    }

    private static UUID name(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
