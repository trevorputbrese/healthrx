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

        // 3b. The live demo starts with a near-empty referral queue — the presenter builds the
        // access-workflow story live via the "New referral" scenario button, instead of starting
        // from a curated seed set (superseded V13 trim). Patients, clinics, medications, payers,
        // and care team members from step 3 above are untouched; this only clears the referral
        // lifecycle and everything hung off it. This is NOT a Flyway migration on purpose — the
        // full seeded lifecycle (V13's curated 14) is still what a fresh deploy and the
        // integration test suite exercise; only the live "Reset Demo" lever produces this state.
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

        // 4b. Seed two referrals already at ACTIVE_THERAPY (walked history, linked therapy, one
        // dispensed 30-day fill each) so "Send at-risk" has a target immediately after a reset —
        // without them the adherence-risk flow needs a full referral walk first. Runs after the
        // actor restore above: the walked history rows are attributed to the SYSTEM actor.
        // Therapies start 7/6 sim-days before the anchor: young enough that PDC still reads
        // "new therapy" (dormant until 14 days of history), covered long enough that risk stays
        // LOW until the scenario is fired.
        seedActiveTherapyReferrals(anchorTs);

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

    /** The canonical walk a seeded active-therapy referral shows in its timeline. */
    private record HistoryStep(String from, String to, int daysBeforeAnchor, String event) {
    }

    private static final java.util.List<HistoryStep> SEEDED_WALK = java.util.List.of(
            new HistoryStep(null, "ELIGIBILITY_IDENTIFIED", 12, "ReferralCreated"),
            new HistoryStep("ELIGIBILITY_IDENTIFIED", "BENEFITS_INVESTIGATION", 11, "BenefitsInvestigationStarted"),
            new HistoryStep("BENEFITS_INVESTIGATION", "PRIOR_AUTH_SUBMITTED", 10, "PriorAuthorizationSubmitted"),
            new HistoryStep("PRIOR_AUTH_SUBMITTED", "PRIOR_AUTH_APPROVED", 9, "PriorAuthorizationApproved"),
            new HistoryStep("PRIOR_AUTH_APPROVED", "READY_TO_FILL", 9, "ReadyToFill"),
            new HistoryStep("READY_TO_FILL", "DELIVERY_SCHEDULED", 8, "DeliveryScheduled"),
            new HistoryStep("DELIVERY_SCHEDULED", "ACTIVE_THERAPY", 7, "TherapyActivated"));

    /**
     * Two deterministic patients (lowest MRNs with a disease-matched medication), each given a
     * referral pre-walked to ACTIVE_THERAPY: full status history, a linked ACTIVE therapy, and
     * one dispensed 30-day fill. The second therapy is created a sim-day later so send-at-risk
     * (which targets the most recently created active therapy) picks it first, leaving the
     * other as a second run. Referral numbers RX-10001/RX-10002 keep the max+1 minting intact.
     */
    private void seedActiveTherapyReferrals(OffsetDateTime anchor) {
        var patients = jdbc.queryForList("""
                select p.id as patient_id, p.primary_owner_id, p.clinic_id, p.payer_id,
                       (select m.id from medications m where m.disease_state = p.disease_state
                        order by m.name limit 1) as medication_id
                from patients p
                where exists (select 1 from medications m where m.disease_state = p.disease_state)
                order by p.demo_mrn limit 2""");

        for (int i = 0; i < patients.size(); i++) {
            var row = patients.get(i);
            java.util.UUID patientId = (java.util.UUID) row.get("patient_id");
            java.util.UUID ownerId = (java.util.UUID) row.get("primary_owner_id");
            java.util.UUID clinicId = (java.util.UUID) row.get("clinic_id");
            java.util.UUID payerId = (java.util.UUID) row.get("payer_id");
            java.util.UUID medicationId = (java.util.UUID) row.get("medication_id");
            java.util.UUID referralId = java.util.UUID.randomUUID();
            java.util.UUID therapyId = java.util.UUID.randomUUID();
            // Patient B runs one sim-day behind patient A, so B's therapy is the newest.
            int lag = patients.size() - 1 - i;
            OffsetDateTime active = anchor.minusDays(SEEDED_WALK.get(6).daysBeforeAnchor() + lag);

            jdbc.update("""
                    insert into referrals (id, referral_number, patient_id, clinic_id, medication_id,
                        payer_id, owner_id, current_status, priority, received_at,
                        benefits_investigation_started_at, pa_required, pa_submitted_at, pa_decided_at,
                        financial_assistance_required, ready_to_fill_at, delivery_scheduled_at,
                        active_therapy_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE_THERAPY', ?, ?, ?, true, ?, ?, false,
                        ?, ?, ?, ?, ?)""",
                    referralId, "RX-1000" + (i + 1), patientId, clinicId, medicationId, payerId,
                    ownerId, i == 0 ? "MEDIUM" : "HIGH",
                    anchor.minusDays(12 + lag), anchor.minusDays(11 + lag), anchor.minusDays(10 + lag),
                    anchor.minusDays(9 + lag), anchor.minusDays(9 + lag), anchor.minusDays(8 + lag),
                    active, anchor.minusDays(12 + lag), active);

            jdbc.update("""
                    insert into therapies (id, patient_id, medication_id, diagnosis, disease_state,
                        status, start_date, current_refill_due_date, created_at)
                    select ?, ?, m.id, m.disease_state, m.disease_state, 'ACTIVE', ?, ?, ?
                    from medications m where m.id = ?""",
                    therapyId, patientId, active.toLocalDate(), active.toLocalDate().plusDays(30),
                    active, medicationId);
            jdbc.update("update referrals set therapy_id = ? where id = ?", therapyId, referralId);

            for (HistoryStep step : SEEDED_WALK) {
                jdbc.update("""
                        insert into referral_status_history (id, referral_id, from_status, to_status,
                            changed_at, changed_by_id, note, phase2_event_type)
                        values (?, ?, ?, ?, ?, ?, ?, ?)""",
                        java.util.UUID.randomUUID(), referralId, step.from(), step.to(),
                        anchor.minusDays(step.daysBeforeAnchor() + lag), SystemActors.SYSTEM,
                        "TherapyActivated".equals(step.event()) ? "Seeded by demo reset — ready for the at-risk scenario." : null,
                        step.event());
            }

            jdbc.update("""
                    insert into fills (id, patient_id, therapy_id, referral_id, fill_number, status,
                        dispensed_at, days_supply, expected_refill_date, created_at)
                    values (?, ?, ?, ?, 1, 'DISPENSED', ?, 30, ?, ?)""",
                    java.util.UUID.randomUUID(), patientId, therapyId, referralId,
                    active.toLocalDate(), active.toLocalDate().plusDays(30), active);
        }
        log.info("Seeded {} active-therapy referrals for the at-risk scenario", patients.size());
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
