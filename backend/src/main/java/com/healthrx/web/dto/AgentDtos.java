package com.healthrx.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.healthrx.web.dto.CommonDtos.NamedRef;

/** Agents view shapes. See phase-3-design.md §8/§9. */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** One agent's card: durable pause state plus live stats and (best-effort) reachability. */
    public record AgentStatus(
            String name,
            String displayName,
            boolean paused,
            boolean reachable,
            Instant lastActivityAt,
            long totalRecommendations,
            long pendingCount,
            long appliedCount,
            long autoAppliedCount) {
    }

    public record AgentsResponse(List<AgentStatus> agents) {
    }

    /** A recommendation row in the Agents feed, trace included. */
    public record Recommendation(
            UUID id,
            String agentName,
            String agentDisplayName,
            NamedRef patient,
            UUID referralId,
            UUID therapyId,
            UUID taskId,
            String status,
            String summary,
            Map<String, Object> recommendation,
            List<Map<String, Object>> trace,
            Instant createdAt,
            Instant decidedAt,
            NamedRef decidedBy) {
    }

    public record Decision(UUID decidedById) {
    }
}
