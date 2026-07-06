package com.shields.healthrx.agent.financial.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Deterministic identity spine + timestamp parsing. */
class AgentIdsTest {

    @Test
    void recommendationAndEventIdsDeriveDeterministically() {
        UUID trigger = UUID.randomUUID();
        UUID rec = AgentIds.recommendationId("financial-assistance", trigger);
        assertThat(rec).isEqualTo(AgentIds.recommendationId("financial-assistance", trigger));
        assertThat(AgentIds.createdEventId(rec)).isEqualTo(AgentIds.createdEventId(rec));
        assertThat(rec).isNotEqualTo(AgentIds.createdEventId(rec));

        // A different agent name or trigger derives a different id.
        assertThat(rec).isNotEqualTo(AgentIds.recommendationId("access-workflow", trigger));
        assertThat(rec).isNotEqualTo(AgentIds.recommendationId("financial-assistance", UUID.randomUUID()));
    }

    @Test
    void guardsParseEpochAndIsoTimestamps() {
        assertThat(Guards.parseTimestamp("1782691200000"))
                .isEqualTo(java.time.Instant.parse("2026-06-29T00:00:00Z"));
        assertThat(Guards.parseTimestamp("2026-06-29 00:00:00+00")).isNotNull();
        assertThat(Guards.parseTimestamp("garbage")).isNull();
    }
}
