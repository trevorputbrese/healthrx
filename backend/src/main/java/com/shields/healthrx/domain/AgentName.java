package com.shields.healthrx.domain;

import java.util.Optional;
import java.util.UUID;

/** The Phase 3 agents: wire names, display names, and their fixed care-team actors. */
public enum AgentName {

    ADHERENCE_RISK("adherence-risk", "Adherence Risk Agent",
            UUID.fromString("00000000-0000-0000-0000-000000000003")),
    ACCESS_WORKFLOW("access-workflow", "Access Workflow Agent",
            UUID.fromString("00000000-0000-0000-0000-000000000004")),
    FINANCIAL_ASSISTANCE("financial-assistance", "Financial Assistance Agent",
            UUID.fromString("00000000-0000-0000-0000-000000000005"));

    private final String wireName;
    private final String displayName;
    private final UUID actorId;

    AgentName(String wireName, String displayName, UUID actorId) {
        this.wireName = wireName;
        this.displayName = displayName;
        this.actorId = actorId;
    }

    public String wireName() {
        return wireName;
    }

    public String displayName() {
        return displayName;
    }

    /** The agent's care_team_members actor id (seeded by V4 with a fixed UUID). */
    public UUID actorId() {
        return actorId;
    }

    public static Optional<AgentName> fromWire(String wireName) {
        for (AgentName a : values()) {
            if (a.wireName.equals(wireName)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }
}
