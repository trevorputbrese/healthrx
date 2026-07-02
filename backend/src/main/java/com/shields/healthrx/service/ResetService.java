package com.shields.healthrx.service;

import java.sql.Connection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.ClockConfig;
import com.shields.healthrx.domain.AgentName;
import com.shields.healthrx.domain.SystemActors;

/**
 * Resets the demo to a pristine state: pauses the simulation, wipes all data, re-applies the
 * deterministic seed (the V2 migration script), restores the non-human actors, and resets the
 * simulated clock to the anchor. Flyway history is untouched.
 */
@Service
public class ResetService {

    private static final Logger log = LoggerFactory.getLogger(ResetService.class);

    /**
     * All data tables, wiped together (CASCADE handles FK order). Excludes simulation_state,
     * agent_control (upserted to paused instead), and flyway.
     */
    private static final String TRUNCATE = """
            truncate table clinics, payers, care_team_members, medications, patients, therapies,
                referrals, referral_status_history, referral_notes, tasks, outreach_events,
                clinical_interventions, fills, processed_events,
                agent_recommendations, agent_tool_calls cascade""";

    private static final int DEFAULT_SPEED = 86400;

    private final JdbcTemplate jdbc;

    public ResetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void resetDemo() {
        Instant anchor = Instant.parse(ClockConfig.DEMO_NOW);
        OffsetDateTime anchorTs = OffsetDateTime.ofInstant(anchor, ZoneOffset.UTC);

        // 1. Pause the simulation and reset the shared clock to the anchor (the generator reads this).
        jdbc.update("""
                update simulation_state
                set enabled = false, current_instant = ?, speed_seconds_per_second = ?, updated_at = ?
                where id = 1""", anchorTs, DEFAULT_SPEED, anchorTs);

        // 2. Wipe all data (idempotency ledger included so the seed re-applies cleanly).
        jdbc.execute(TRUNCATE);

        // 3. Re-apply the deterministic seed (same script Flyway runs for V2).
        jdbc.execute((Connection con) -> {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/migration/V2__seed_data.sql"));
            return null;
        });

        // 4. Restore the non-human actors (seeded by V3/V4, not in the V2 seed script).
        jdbc.update("""
                insert into care_team_members (id, display_name, role, email, active, created_at)
                values (?, 'HealthRx System', 'System', null, true, ?) on conflict (id) do nothing""",
                SystemActors.SYSTEM, anchorTs);
        jdbc.update("""
                insert into care_team_members (id, display_name, role, email, active, created_at)
                values (?, 'Care Agent', 'AI Agent', null, true, ?) on conflict (id) do nothing""",
                SystemActors.AGENT, anchorTs);
        for (AgentName agent : AgentName.values()) {
            jdbc.update("""
                    insert into care_team_members (id, display_name, role, email, active, created_at)
                    values (?, ?, 'AI Agent', null, true, ?) on conflict (id) do nothing""",
                    agent.actorId(), agent.displayName(), anchorTs);
        }

        // 5. Pause both agents (they resume explicitly from the Agents view; design §2 guardrail 5).
        for (AgentName agent : AgentName.values()) {
            jdbc.update("""
                    insert into agent_control (agent_name, paused, updated_at)
                    values (?, true, ?)
                    on conflict (agent_name) do update set paused = true, updated_at = excluded.updated_at""",
                    agent.wireName(), anchorTs);
        }

        log.warn("DEMO RESET: data wiped and reseeded; simulation paused at {}; agents paused", anchor);
    }
}
