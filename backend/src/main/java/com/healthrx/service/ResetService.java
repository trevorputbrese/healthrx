package com.healthrx.service;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.healthrx.config.ClockConfig;
import com.healthrx.domain.AgentName;
import com.healthrx.domain.SystemActors;

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
    private final RestClient payerPortal;
    private final RestClient assistancePortal;

    public ResetService(JdbcTemplate jdbc,
            @Value("${healthrx.payer-portal.url:}") String payerPortalUrl,
            @Value("${healthrx.assistance-portal.url:}") String assistancePortalUrl) {
        this.jdbc = jdbc;
        this.payerPortal = resetClientFor(payerPortalUrl);
        this.assistancePortal = resetClientFor(assistancePortalUrl);
    }

    /** A partner portal's URL is optional (local dev has neither); null means "skip the reset call". */
    private static RestClient resetClientFor(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    @Transactional
    public void resetDemo() {
        Instant anchor = Instant.parse(ClockConfig.DEMO_NOW);
        OffsetDateTime anchorTs = OffsetDateTime.ofInstant(anchor, ZoneOffset.UTC);

        // 1. Pause the simulation and reset the shared clock to the anchor (the generator reads
        // this); also restore the ambient-events toggle to on, in case a presenter left it off.
        jdbc.update("""
                update simulation_state
                set enabled = false, current_instant = ?, speed_seconds_per_second = ?,
                    ambient_enabled = true, updated_at = ?
                where id = 1""", anchorTs, DEFAULT_SPEED, anchorTs);

        // 2. Wipe all data (idempotency ledger included so the seed re-applies cleanly).
        jdbc.execute(TRUNCATE);

        // 3. Re-apply the deterministic seed (same script Flyway runs for V2), then collapse to
        // the single signed-in user and rename them to the presenter (V8-V11 — the V2 seed
        // itself stays untouched; Flyway checksums forbid editing an applied migration).
        jdbc.execute((Connection con) -> {
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/migration/V2__seed_data.sql"));
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/migration/V8__single_care_team_user.sql"));
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/migration/V9__rename_single_user_trevor.sql"));
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/migration/V10__unique_patient_names.sql"));
            ScriptUtils.executeSqlScript(con, new ClassPathResource("db/migration/V11__globally_unique_patient_names.sql"));
            return null;
        });

        // 3b. The live demo now starts with an empty referral queue — the presenter builds up
        // all referral-lifecycle material live via the "New referral" scenario button, instead
        // of starting from a curated seed set (superseded V13 trim). Patients, clinics,
        // medications, payers, and care team members from step 3 above are untouched; this only
        // clears the referral lifecycle and everything hung off it. This is NOT a Flyway
        // migration on purpose — the full seeded lifecycle (V13's curated 14) is still what a
        // fresh deploy and the integration test suite exercise; only the live "Reset Demo" lever
        // leaves the queue empty.
        clearReferralLifecycle();

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

        // 6. Best-effort: reset both external partners' submission memory so a re-run of the
        // demo adjudicates identically (same referral numbers reseed, and each portal's
        // deterministic-per-referral rule would otherwise silently give a different answer).
        resetPartner(payerPortal, "Payer portal");
        resetPartner(assistancePortal, "Assistance portal");
    }

    /**
     * Empties the referral queue: everything hung off a referral goes first (FK order — fills
     * reference therapies, referrals reference therapies, so therapies must be deleted last),
     * then the referrals themselves, then the now-orphaned therapies. Patients/clinics/
     * medications/payers/care team members are untouched.
     */
    private void clearReferralLifecycle() {
        jdbc.update("delete from fills");
        jdbc.update("delete from referral_status_history");
        jdbc.update("delete from referral_notes");
        jdbc.update("delete from tasks where referral_id is not null");
        jdbc.update("delete from outreach_events where referral_id is not null");
        jdbc.update("delete from clinical_interventions where referral_id is not null");
        jdbc.update("delete from referrals");
        jdbc.update("delete from therapies");
    }

    private void resetPartner(RestClient client, String label) {
        if (client == null) {
            return;
        }
        try {
            client.post().uri("/api/admin/reset").retrieve().toBodilessEntity();
            log.info("{} submission memory reset", label);
        } catch (Exception e) {
            log.warn("{} reset failed (offline?) — reruns may adjudicate differently", label, e);
        }
    }
}
