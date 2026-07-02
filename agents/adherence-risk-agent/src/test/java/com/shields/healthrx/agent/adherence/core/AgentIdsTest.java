package com.shields.healthrx.agent.adherence.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The deterministic identity spine (phase-3-design.md §6) — replays must derive identical ids. */
class AgentIdsTest {

    @Test
    void sameTriggerAlwaysDerivesTheSameIds() {
        UUID trigger = UUID.fromString("11111111-2222-3333-4444-555555555555");

        UUID rec1 = AgentIds.recommendationId("adherence-risk", trigger);
        UUID rec2 = AgentIds.recommendationId("adherence-risk", trigger);
        assertThat(rec1).isEqualTo(rec2);

        assertThat(AgentIds.createdEventId(rec1)).isEqualTo(AgentIds.createdEventId(rec2));
        assertThat(AgentIds.appliedEventId(rec1)).isEqualTo(AgentIds.appliedEventId(rec2));
    }

    @Test
    void distinctTriggersAgentsAndLifecycleStagesDeriveDistinctIds() {
        UUID triggerA = UUID.randomUUID();
        UUID triggerB = UUID.randomUUID();

        UUID recA = AgentIds.recommendationId("adherence-risk", triggerA);
        assertThat(recA).isNotEqualTo(AgentIds.recommendationId("adherence-risk", triggerB));
        assertThat(recA).isNotEqualTo(AgentIds.recommendationId("access-workflow", triggerA));
        assertThat(AgentIds.createdEventId(recA)).isNotEqualTo(AgentIds.appliedEventId(recA));
        assertThat(recA).isNotEqualTo(AgentIds.createdEventId(recA));
    }

    @Test
    void guardsParsePostgresTimestampFormats() {
        assertThat(Guards.parseTimestamp("2026-06-29 00:00:00+00")).isNotNull();
        assertThat(Guards.parseTimestamp("2026-06-29T00:00:00Z")).isNotNull();
        assertThat(Guards.parseTimestamp("2026-06-29 12:30:45.123456+00")).isNotNull();
        // The Postgres MCP server serializes timestamptz as epoch millis on the wire.
        assertThat(Guards.parseTimestamp("1782691200000"))
                .isEqualTo(java.time.Instant.parse("2026-06-29T00:00:00Z"));
        assertThat(Guards.parseTimestamp("1782691200"))
                .isEqualTo(java.time.Instant.parse("2026-06-29T00:00:00Z"));
        assertThat(Guards.parseTimestamp("garbage")).isNull();
        assertThat(Guards.parseTimestamp("null")).isNull();
    }
}
