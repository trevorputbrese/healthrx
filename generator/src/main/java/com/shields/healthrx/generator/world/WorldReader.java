package com.shields.healthrx.generator.world;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads current world state to choose realistic targets for generated events. */
@Repository
public class WorldReader {

    private final JdbcTemplate jdbc;

    public WorldReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ReferralRef(UUID id, String currentStatus, UUID patientId, UUID therapyId) {
    }

    public record NewReferralSeed(UUID patientId, UUID clinicId, UUID medicationId, UUID payerId, UUID ownerId) {
    }

    public record TherapyRef(UUID id, UUID patientId, LocalDate currentRefillDueDate, int daysSupply, UUID ownerId) {
    }

    public record EngagementTarget(UUID patientId, UUID ownerId, UUID referralId) {
    }

    private static UUID uuid(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        return rs.getObject(col, UUID.class);
    }

    /**
     * A random in-flight referral (not active/cancelled) to advance one step. Referrals whose
     * prior auth was submitted within the last 6 simulated days are excluded: the Access
     * Workflow Agent owns fresh PA decisions (it contacts the payer portal within seconds), and
     * the ambient stream adjudicating the same referral in parallel could visibly contradict
     * the payer's on-screen decision. Older submissions remain fair game so the world still
     * moves when the agent is paused.
     */
    public Optional<ReferralRef> pickAdvanceableReferral(java.time.Instant simNow) {
        List<ReferralRef> rows = jdbc.query("""
                select id, current_status, patient_id, therapy_id from referrals
                where current_status not in ('ACTIVE_THERAPY', 'CANCELLED')
                  and not (current_status = 'PRIOR_AUTH_SUBMITTED'
                           and pa_submitted_at > ?::timestamptz - interval '6 days')
                order by random() limit 1""",
                (rs, i) -> new ReferralRef(uuid(rs, "id"), rs.getString("current_status"),
                        uuid(rs, "patient_id"), uuid(rs, "therapy_id")),
                simNow.toString());
        return rows.stream().findFirst();
    }

    /** A random existing patient + a medication in their disease state, for a new referral. */
    public Optional<NewReferralSeed> pickNewReferralSeed() {
        List<NewReferralSeed> rows = jdbc.query("""
                select p.id as patient_id, p.clinic_id, p.payer_id, p.primary_owner_id as owner_id,
                       (select m.id from medications m where m.disease_state = p.disease_state and m.active
                        order by random() limit 1) as medication_id
                from patients p order by random() limit 1""",
                (rs, i) -> new NewReferralSeed(uuid(rs, "patient_id"), uuid(rs, "clinic_id"),
                        uuid(rs, "medication_id"), uuid(rs, "payer_id"), uuid(rs, "owner_id")));
        return rows.stream().filter(s -> s.medicationId() != null).findFirst();
    }

    /** A random active therapy whose refill is due or overdue as of the simulated date. */
    public Optional<TherapyRef> pickRefillableTherapy(LocalDate asOf) {
        List<TherapyRef> rows = jdbc.query("""
                select t.id, t.patient_id, t.current_refill_due_date, p.primary_owner_id as owner_id,
                       coalesce((select f.days_supply from fills f where f.therapy_id = t.id
                                 and f.status = 'DISPENSED' order by f.fill_number desc limit 1), 30) as days_supply
                from therapies t join patients p on p.id = t.patient_id
                where t.status = 'ACTIVE' and t.current_refill_due_date is not null
                  and t.current_refill_due_date <= ?
                order by random() limit 1""",
                (rs, i) -> new TherapyRef(uuid(rs, "id"), uuid(rs, "patient_id"),
                        rs.getObject("current_refill_due_date", LocalDate.class),
                        rs.getInt("days_supply"), uuid(rs, "owner_id")),
                asOf.plusDays(3));
        return rows.stream().findFirst();
    }

    /**
     * The oldest referral that can plausibly have a PA submitted next: prefer one already in
     * BENEFITS_INVESTIGATION, else fall back to ELIGIBILITY_IDENTIFIED (the scenario chains the
     * benefits step first). Oldest-first keeps the presenter demo deterministic.
     */
    public Optional<ReferralRef> pickPaSubmittableReferral() {
        List<ReferralRef> rows = jdbc.query("""
                select id, current_status, patient_id, therapy_id from referrals
                where current_status in ('BENEFITS_INVESTIGATION', 'ELIGIBILITY_IDENTIFIED')
                order by case current_status when 'BENEFITS_INVESTIGATION' then 0 else 1 end,
                         received_at asc
                limit 1""",
                (rs, i) -> new ReferralRef(uuid(rs, "id"), rs.getString("current_status"),
                        uuid(rs, "patient_id"), uuid(rs, "therapy_id")));
        return rows.stream().findFirst();
    }

    /** A random active-therapy patient (with their latest referral) for outreach / interventions. */
    public Optional<EngagementTarget> pickEngagementTarget() {
        List<EngagementTarget> rows = jdbc.query("""
                select t.patient_id, p.primary_owner_id as owner_id,
                       (select r.id from referrals r where r.patient_id = t.patient_id
                        order by r.received_at desc limit 1) as referral_id
                from therapies t join patients p on p.id = t.patient_id
                where t.status = 'ACTIVE' order by random() limit 1""",
                (rs, i) -> new EngagementTarget(uuid(rs, "patient_id"), uuid(rs, "owner_id"),
                        uuid(rs, "referral_id")));
        return rows.stream().findFirst();
    }

    /** Finds a patient by demo MRN (used by presenter scenarios). */
    public Optional<EngagementTarget> findPatientByMrn(String demoMrn) {
        List<EngagementTarget> rows = jdbc.query("""
                select p.id as patient_id, p.primary_owner_id as owner_id,
                       (select r.id from referrals r where r.patient_id = p.id
                        order by r.received_at desc limit 1) as referral_id
                from patients p where p.demo_mrn = ?""",
                (rs, i) -> new EngagementTarget(uuid(rs, "patient_id"), uuid(rs, "owner_id"),
                        uuid(rs, "referral_id")),
                demoMrn);
        return rows.stream().findFirst();
    }

    public Optional<TherapyRef> findActiveTherapyByMrn(String demoMrn) {
        List<TherapyRef> rows = jdbc.query("""
                select t.id, t.patient_id, t.current_refill_due_date, p.primary_owner_id as owner_id,
                       coalesce((select f.days_supply from fills f where f.therapy_id = t.id
                                 and f.status = 'DISPENSED' order by f.fill_number desc limit 1), 30) as days_supply
                from therapies t join patients p on p.id = t.patient_id
                where p.demo_mrn = ? and t.status = 'ACTIVE'
                order by t.created_at desc limit 1""",
                (rs, i) -> new TherapyRef(uuid(rs, "id"), uuid(rs, "patient_id"),
                        rs.getObject("current_refill_due_date", LocalDate.class),
                        rs.getInt("days_supply"), uuid(rs, "owner_id")),
                demoMrn);
        return rows.stream().findFirst();
    }
}
