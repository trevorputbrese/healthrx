package com.shields.healthrx.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.repo.SimulationStateRepository;
import com.shields.healthrx.web.ApiException;

/**
 * Exercises the event dispatcher / write paths directly against a real Postgres (no broker), and
 * verifies the simulated clock. Idempotency + the AMQP path are covered by {@link EventConsumerIT}.
 */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class EventApplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    com.shields.healthrx.service.EventApplicationService app;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SimulationStateRepository simulation;
    @Autowired
    Clock clock;

    private UUID one(String sql) {
        return jdbc.queryForObject(sql, UUID.class);
    }

    /** A patient+medication pair with no existing referral (the consumer skips duplicates). */
    private Map<String, Object> unreferredPair() {
        return jdbc.queryForMap("""
                select p.id as patient_id, m.id as medication_id
                from patients p join medications m on m.disease_state = p.disease_state and m.active
                where not exists (select 1 from referrals r
                                  where r.patient_id = p.id and r.medication_id = m.id)
                limit 1""");
    }

    private EventEnvelope env(WorkflowEventType type, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type.wireName(),
                Instant.parse("2026-07-01T12:00:00Z"), "test", payload);
    }

    @Test
    void referralCreatedInsertsReferralAndHistory() {
        UUID referralId = UUID.randomUUID();
        Map<String, Object> pair = unreferredPair();
        app.apply(env(WorkflowEventType.REFERRAL_CREATED, Map.of(
                "referralId", referralId.toString(),
                "patientId", pair.get("patient_id").toString(),
                "clinicId", one("select id from clinics limit 1").toString(),
                "medicationId", pair.get("medication_id").toString(),
                "payerId", one("select id from payers limit 1").toString(),
                "ownerId", one("select id from care_team_members where active limit 1").toString(),
                "priority", "HIGH",
                "paRequired", true)));

        assertThat(jdbc.queryForObject(
                "select current_status from referrals where id = ?", String.class, referralId))
                .isEqualTo("ELIGIBILITY_IDENTIFIED");
        assertThat(jdbc.queryForObject(
                "select count(*) from referral_status_history where referral_id = ? and phase2_event_type = 'ReferralCreated'",
                Integer.class, referralId)).isEqualTo(1);
    }

    @Test
    void duplicateReferralForSamePatientAndMedicationIsSkipped() {
        // Any seeded referral's patient+medication pair already exists -> the consumer skips.
        Map<String, Object> existing = jdbc.queryForMap(
                "select patient_id, medication_id from referrals limit 1");
        UUID referralId = UUID.randomUUID();
        app.apply(env(WorkflowEventType.REFERRAL_CREATED, Map.of(
                "referralId", referralId.toString(),
                "patientId", existing.get("patient_id").toString(),
                "clinicId", one("select id from clinics limit 1").toString(),
                "medicationId", existing.get("medication_id").toString(),
                "payerId", one("select id from payers limit 1").toString(),
                "ownerId", one("select id from care_team_members where active limit 1").toString(),
                "priority", "HIGH",
                "paRequired", true)));

        assertThat(jdbc.queryForObject(
                "select count(*) from referrals where id = ?", Integer.class, referralId)).isZero();
    }

    @Test
    void benefitsInvestigationStartedAdvancesAndStamps() {
        UUID referralId = one("select id from referrals where current_status = 'ELIGIBILITY_IDENTIFIED' limit 1");
        app.apply(env(WorkflowEventType.BENEFITS_INVESTIGATION_STARTED, Map.of("referralId", referralId.toString())));
        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?", String.class, referralId))
                .isEqualTo("BENEFITS_INVESTIGATION");
        assertThat(jdbc.queryForObject(
                "select benefits_investigation_started_at is not null from referrals where id = ?", Boolean.class, referralId))
                .isTrue();
    }

    @Test
    void prescriptionFilledAddsDispensedFillAndRollsRefillDue() {
        UUID therapyId = one("select id from therapies where status = 'ACTIVE' limit 1");
        UUID patientId = jdbc.queryForObject("select patient_id from therapies where id = ?", UUID.class, therapyId);
        int before = jdbc.queryForObject("select count(*) from fills where therapy_id = ?", Integer.class, therapyId);

        app.apply(env(WorkflowEventType.PRESCRIPTION_FILLED, Map.of(
                "fillId", UUID.randomUUID().toString(),
                "therapyId", therapyId.toString(),
                "patientId", patientId.toString(),
                "daysSupply", 30,
                "dispensedAt", "2026-07-01")));

        assertThat(jdbc.queryForObject("select count(*) from fills where therapy_id = ? and status = 'DISPENSED'",
                Integer.class, therapyId)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from fills where therapy_id = ?", Integer.class, therapyId))
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select current_refill_due_date from therapies where id = ?",
                java.time.LocalDate.class, therapyId)).isEqualTo(java.time.LocalDate.parse("2026-07-31"));
    }

    @Test
    void refillMissedAddsMissedFill() {
        UUID therapyId = one("select id from therapies where status = 'ACTIVE' limit 1");
        UUID patientId = jdbc.queryForObject("select patient_id from therapies where id = ?", UUID.class, therapyId);
        app.apply(env(WorkflowEventType.REFILL_MISSED, Map.of(
                "fillId", UUID.randomUUID().toString(),
                "therapyId", therapyId.toString(),
                "patientId", patientId.toString(),
                "expectedRefillDate", "2026-06-20")));
        assertThat(jdbc.queryForObject("select count(*) from fills where therapy_id = ? and status = 'MISSED'",
                Integer.class, therapyId)).isGreaterThan(0);
    }

    @Test
    void unknownEventTypeIsRejected() {
        EventEnvelope bad = new EventEnvelope(UUID.randomUUID(), "NotARealEvent",
                Instant.parse("2026-07-01T12:00:00Z"), "test", Map.of());
        assertThatThrownBy(() -> app.apply(bad)).isInstanceOf(ApiException.class);
    }

    @Test
    void nonApplicableTransitionIsRejected() {
        UUID active = one("select id from referrals where current_status = 'ACTIVE_THERAPY' limit 1");
        // ACTIVE_THERAPY cannot go back to BENEFITS_INVESTIGATION.
        assertThatThrownBy(() -> app.apply(
                env(WorkflowEventType.BENEFITS_INVESTIGATION_STARTED, Map.of("referralId", active.toString()))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void redeliveredMilestoneEventToCurrentStatusIsIdempotentNoOp() {
        UUID referralId = one("select id from referrals where current_status = 'ELIGIBILITY_IDENTIFIED' limit 1");
        app.apply(env(WorkflowEventType.BENEFITS_INVESTIGATION_STARTED, Map.of("referralId", referralId.toString())));
        // A second (distinct-eventId) event targeting the now-current status must be a no-op, not an error.
        app.apply(env(WorkflowEventType.BENEFITS_INVESTIGATION_STARTED, Map.of("referralId", referralId.toString())));
        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?", String.class, referralId))
                .isEqualTo("BENEFITS_INVESTIGATION");
    }

    @Test
    void financialAssistanceFoundRecordsAmountsEvenWhenPastReview() {
        UUID active = one("select id from referrals where current_status = 'ACTIVE_THERAPY' limit 1");
        // ACTIVE_THERAPY cannot transition into FINANCIAL_ASSISTANCE_REVIEW; amounts must still record.
        app.apply(env(WorkflowEventType.FINANCIAL_ASSISTANCE_FOUND, Map.of(
                "referralId", active.toString(), "securedAmount", 5000.00)));
        assertThat(jdbc.queryForObject(
                "select financial_assistance_secured_amount from referrals where id = ?",
                java.math.BigDecimal.class, active)).isEqualByComparingTo("5000.00");
        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?", String.class, active))
                .isEqualTo("ACTIVE_THERAPY");
    }

    @Test
    void refillMissedRollsCurrentRefillDueDateToThePast() {
        UUID therapyId = one("select id from therapies where status = 'ACTIVE' order by id limit 1 offset 5");
        UUID patientId = jdbc.queryForObject("select patient_id from therapies where id = ?", UUID.class, therapyId);
        app.apply(env(WorkflowEventType.REFILL_MISSED, Map.of(
                "fillId", UUID.randomUUID().toString(),
                "therapyId", therapyId.toString(),
                "patientId", patientId.toString(),
                "expectedRefillDate", "2026-06-15")));
        assertThat(jdbc.queryForObject("select current_refill_due_date from therapies where id = ?",
                java.time.LocalDate.class, therapyId)).isEqualTo(java.time.LocalDate.parse("2026-06-15"));
    }

    @Test
    void fullJourneyToActiveTherapyAutoCreatesAndLinksTherapy() {
        UUID referralId = UUID.randomUUID();
        Map<String, Object> pair = unreferredPair();
        app.apply(env(WorkflowEventType.REFERRAL_CREATED, Map.of(
                "referralId", referralId.toString(),
                "patientId", pair.get("patient_id").toString(),
                "clinicId", one("select id from clinics limit 1").toString(),
                "medicationId", pair.get("medication_id").toString(),
                "payerId", one("select id from payers limit 1").toString(),
                "ownerId", one("select id from care_team_members where active limit 1").toString(),
                "priority", "MEDIUM", "paRequired", true)));

        for (WorkflowEventType t : List.of(
                WorkflowEventType.BENEFITS_INVESTIGATION_STARTED,
                WorkflowEventType.PRIOR_AUTHORIZATION_SUBMITTED,
                WorkflowEventType.PRIOR_AUTHORIZATION_APPROVED,
                WorkflowEventType.READY_TO_FILL,
                WorkflowEventType.DELIVERY_SCHEDULED,
                WorkflowEventType.THERAPY_ACTIVATED)) {
            app.apply(env(t, Map.of("referralId", referralId.toString())));
        }

        assertThat(jdbc.queryForObject("select current_status from referrals where id = ?", String.class, referralId))
                .isEqualTo("ACTIVE_THERAPY");
        UUID therapyId = jdbc.queryForObject("select therapy_id from referrals where id = ?", UUID.class, referralId);
        assertThat(therapyId).isNotNull();
        assertThat(jdbc.queryForObject("select status from therapies where id = ?", String.class, therapyId))
                .isEqualTo("ACTIVE");
    }

    @Test
    void simulatedClockIsUsedAsNowEvenWhenPaused() {
        // enabled=false (paused): now() must still reflect current_instant, not revert to the pin.
        jdbc.update("update simulation_state set enabled = false, current_instant = ? where id = 1",
                java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        try {
            AppTime fresh = new AppTime(clock, simulation);
            assertThat(fresh.now()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        } finally {
            jdbc.update("update simulation_state set enabled = false, current_instant = ? where id = 1",
                    java.sql.Timestamp.from(Instant.parse("2026-06-29T00:00:00Z")));
        }
    }
}
