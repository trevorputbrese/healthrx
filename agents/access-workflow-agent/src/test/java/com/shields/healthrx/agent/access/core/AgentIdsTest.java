package com.shields.healthrx.agent.access.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Deterministic identity spine incl. scan-episode ids (phase-3-design.md §6). */
class AgentIdsTest {

    @Test
    void scanEpisodesAreDeterministicPerEpisodeAndDistinctAcrossEpisodes() {
        UUID referral = UUID.fromString("11111111-2222-3333-4444-555555555555");

        UUID a = AgentIds.scanEpisodeId("access-workflow", referral, "PA_PENDING", "1782691200000", 0);
        UUID b = AgentIds.scanEpisodeId("access-workflow", referral, "PA_PENDING", "1782691200000", 0);
        assertThat(a).isEqualTo(b);

        // A dismissal bumps the decided count -> a new episode id (re-trigger allowed).
        assertThat(a).isNotEqualTo(
                AgentIds.scanEpisodeId("access-workflow", referral, "PA_PENDING", "1782691200000", 1));
        // A different rule or stuck-entry time is a different episode.
        assertThat(a).isNotEqualTo(
                AgentIds.scanEpisodeId("access-workflow", referral, "STATUS_STALLED", "1782691200000", 0));
        assertThat(a).isNotEqualTo(
                AgentIds.scanEpisodeId("access-workflow", referral, "PA_PENDING", "1782777600000", 0));
    }

    @Test
    void recommendationAndEventIdsDeriveDeterministically() {
        UUID episode = UUID.randomUUID();
        UUID rec = AgentIds.recommendationId("access-workflow", episode);
        assertThat(rec).isEqualTo(AgentIds.recommendationId("access-workflow", episode));
        assertThat(AgentIds.createdEventId(rec)).isEqualTo(AgentIds.createdEventId(rec));
        assertThat(rec).isNotEqualTo(AgentIds.createdEventId(rec));
    }

    @Test
    void guardsParseEpochAndIsoTimestamps() {
        assertThat(Guards.parseTimestamp("1782691200000"))
                .isEqualTo(java.time.Instant.parse("2026-06-29T00:00:00Z"));
        assertThat(Guards.parseTimestamp("2026-06-29 00:00:00+00")).isNotNull();
        assertThat(Guards.parseTimestamp("garbage")).isNull();
    }
}
