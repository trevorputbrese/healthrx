package com.shields.healthrx;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shields.healthrx.domain.SystemActors;
import com.shields.healthrx.service.ResetService;

/** Verifies the one-click reset wipes mutated state and restores the deterministic seed + clock. */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class ResetServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ResetService reset;
    @Autowired
    JdbcTemplate jdbc;

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void resetWipesMutationsAndRestoresSeedAndClock() {
        // Dirty the world: drop some data, advance/enable the clock, add an idempotency row.
        jdbc.update("delete from fills");
        jdbc.update("update simulation_state set enabled = true, ambient_enabled = false, current_instant = ? "
                + "where id = 1", java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        jdbc.update("insert into processed_events (event_id, event_type, source, status, processed_at) "
                + "values (?, 'Test', 'test', 'APPLIED', now())", UUID.randomUUID());
        // Dirty the Phase 3 agent state too: a recommendation + ledger row, and an un-paused agent.
        jdbc.update("insert into agent_recommendations (id, agent_name, patient_id, status, summary, "
                + "recommendation, created_at) select ?, 'adherence-risk', id, 'PENDING', 'test', "
                + "'{}'::jsonb, now() from patients limit 1", UUID.randomUUID());
        jdbc.update("insert into agent_tool_calls (recommendation_id, tool_name, result, created_at) "
                + "values (?, 'log_outreach', '{}'::jsonb, now())", UUID.randomUUID());
        jdbc.update("update agent_control set paused = false where agent_name = 'adherence-risk'");
        assertThat(count("fills")).isZero();

        reset.resetDemo();

        // Seed counts restored (V13 trims referrals to 14; patients/therapies follow accordingly).
        assertThat(count("referrals")).isEqualTo(14);
        assertThat(count("patients")).isEqualTo(80);
        assertThat(count("therapies")).isEqualTo(4);
        assertThat(count("fills")).isGreaterThan(0);
        assertThat(count("processed_events")).isZero();
        // 8 seeded care team members + System, Care Agent, and the three Phase 3 agent actors.
        assertThat(count("care_team_members")).isEqualTo(13);
        assertThat(jdbc.queryForObject("select count(*) from care_team_members where id = ?",
                Integer.class, SystemActors.SYSTEM)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from care_team_members where role = 'AI Agent'",
                Integer.class)).isEqualTo(4);

        // Phase 3 agent state: recommendations/ledger truncated, all three agents paused (V4/V14 + reset).
        assertThat(count("agent_recommendations")).isZero();
        assertThat(count("agent_tool_calls")).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from agent_control where paused = true", Integer.class)).isEqualTo(3);

        // Clock reset + paused; ambient events restored to on even if a presenter had left it off.
        assertThat(jdbc.queryForObject("select enabled from simulation_state where id = 1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select ambient_enabled from simulation_state where id = 1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select current_instant from simulation_state where id = 1", OffsetDateTime.class)
                .toInstant()).isEqualTo(Instant.parse("2026-06-29T00:00:00Z"));

        // A fixed demo scenario is back.
        assertThat(jdbc.queryForObject("select count(*) from referrals where referral_number = 'RX-10003'",
                Integer.class)).isEqualTo(1);
    }
}
