package com.shields.healthrx.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Therapy write operations (Phase 1: activation as a side-effect of referral activation). */
@Repository
public class TherapyRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TherapyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Marks a therapy ACTIVE and sets its start_date if not already set. */
    public void activate(UUID therapyId, LocalDate startDate) {
        jdbc.update("""
                update therapies set status = 'ACTIVE', start_date = coalesce(start_date, :start)
                where id = :id""",
                new MapSqlParameterSource("id", therapyId).addValue("start", startDate));
    }

    /** Rolls the canonical next-refill date after a dispense. */
    public void setCurrentRefillDueDate(UUID therapyId, LocalDate dueDate) {
        jdbc.update("update therapies set current_refill_due_date = :due where id = :id",
                new MapSqlParameterSource("id", therapyId).addValue("due", dueDate));
    }

    public boolean exists(UUID therapyId) {
        Long n = jdbc.queryForObject("select count(*) from therapies where id = :id",
                new MapSqlParameterSource("id", therapyId), Long.class);
        return n != null && n > 0;
    }

    /**
     * Creates an ACTIVE therapy for a referral that has none yet (event-driven activation),
     * copying patient/medication/disease-state from the referral. Diagnosis defaults to the
     * disease state for the demo.
     */
    public void createActiveFromReferral(UUID therapyId, UUID referralId, LocalDate startDate, Instant createdAt) {
        jdbc.update("""
                insert into therapies (id, patient_id, medication_id, diagnosis, disease_state, status,
                                       start_date, created_at)
                select :tid, r.patient_id, r.medication_id, m.disease_state, m.disease_state, 'ACTIVE',
                       :start, :created
                from referrals r join medications m on m.id = r.medication_id
                where r.id = :rid""",
                new MapSqlParameterSource()
                        .addValue("tid", therapyId).addValue("rid", referralId)
                        .addValue("start", startDate).addValue("created", Columns.ts(createdAt)));
    }
}
