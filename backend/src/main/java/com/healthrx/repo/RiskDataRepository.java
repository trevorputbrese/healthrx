package com.healthrx.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.healthrx.domain.InterventionType;
import com.healthrx.domain.OutreachOutcome;
import com.healthrx.domain.TaskType;
import com.healthrx.metric.RefillRiskCalculator.InterventionPoint;
import com.healthrx.metric.RefillRiskCalculator.OutreachPoint;
import com.healthrx.metric.RefillRiskCalculator.TaskPoint;

/** Batch loaders for the inputs of the refill-risk calculation. */
@Repository
public class RiskDataRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RiskDataRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ActiveTherapyRow(
            UUID therapyId, UUID patientId, UUID medicationId, String medicationName,
            String status, LocalDate startDate, LocalDate currentRefillDueDate) {
    }

    public record DispensedFillRow(UUID therapyId, LocalDate dispensedAt, int daysSupply, LocalDate expectedRefillDate) {
    }

    public List<ActiveTherapyRow> activeTherapies() {
        return jdbc.query("""
                select t.id, t.patient_id, t.medication_id, m.name as medication_name,
                       t.status, t.start_date, t.current_refill_due_date
                from therapies t join medications m on m.id = t.medication_id
                where t.status = 'ACTIVE' and (t.start_date is null or t.start_date <= current_date)
                  and (t.end_date is null or t.end_date > current_date)""",
                (rs, i) -> mapTherapy(rs));
    }

    /** All therapies for a patient (any status), for the workbench therapy list. */
    public List<ActiveTherapyRow> therapiesForPatient(UUID patientId) {
        return jdbc.query("""
                select t.id, t.patient_id, t.medication_id, m.name as medication_name,
                       t.status, t.start_date, t.current_refill_due_date
                from therapies t join medications m on m.id = t.medication_id
                where t.patient_id = :pid
                order by t.created_at asc""",
                new MapSqlParameterSource("pid", patientId), (rs, i) -> mapTherapy(rs));
    }

    /** Therapies by id (any status), for resolving queue-row refill risk. */
    public List<ActiveTherapyRow> therapiesByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select t.id, t.patient_id, t.medication_id, m.name as medication_name,
                       t.status, t.start_date, t.current_refill_due_date
                from therapies t join medications m on m.id = t.medication_id
                where t.id in (:ids)""",
                new MapSqlParameterSource("ids", ids), (rs, i) -> mapTherapy(rs));
    }

    /** All therapies for a set of patients (any status), for the patient directory risk rollup. */
    public List<ActiveTherapyRow> therapiesForPatients(Collection<UUID> patientIds) {
        if (patientIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select t.id, t.patient_id, t.medication_id, m.name as medication_name,
                       t.status, t.start_date, t.current_refill_due_date
                from therapies t join medications m on m.id = t.medication_id
                where t.patient_id in (:ids)""",
                new MapSqlParameterSource("ids", patientIds), (rs, i) -> mapTherapy(rs));
    }

    private static ActiveTherapyRow mapTherapy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ActiveTherapyRow(
                Columns.uuid(rs, "id"), Columns.uuid(rs, "patient_id"), Columns.uuid(rs, "medication_id"),
                rs.getString("medication_name"), rs.getString("status"),
                Columns.localDate(rs, "start_date"), Columns.localDate(rs, "current_refill_due_date"));
    }

    public Map<UUID, List<DispensedFillRow>> dispensedFillsByTherapy(Collection<UUID> therapyIds) {
        Map<UUID, List<DispensedFillRow>> out = new LinkedHashMap<>();
        if (therapyIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select therapy_id, dispensed_at, days_supply, expected_refill_date
                from fills
                where status = 'DISPENSED' and dispensed_at is not null and therapy_id in (:ids)
                order by dispensed_at asc""",
                new MapSqlParameterSource("ids", therapyIds), rs -> {
                    UUID tid = Columns.uuid(rs, "therapy_id");
                    out.computeIfAbsent(tid, k -> new java.util.ArrayList<>()).add(new DispensedFillRow(
                            tid, Columns.localDate(rs, "dispensed_at"), rs.getInt("days_supply"),
                            Columns.localDate(rs, "expected_refill_date")));
                });
        return out;
    }

    public Map<UUID, List<OutreachPoint>> recentOutreachByPatient(Collection<UUID> patientIds, Instant cutoff) {
        Map<UUID, List<OutreachPoint>> out = new LinkedHashMap<>();
        if (patientIds.isEmpty()) {
            return out;
        }
        var params = new MapSqlParameterSource("ids", patientIds).addValue("cutoff", Columns.ts(cutoff));
        jdbc.query("""
                select patient_id, occurred_at, outcome from outreach_events
                where patient_id in (:ids) and occurred_at >= :cutoff""",
                params, rs -> {
                    UUID pid = Columns.uuid(rs, "patient_id");
                    out.computeIfAbsent(pid, k -> new java.util.ArrayList<>()).add(new OutreachPoint(
                            Columns.instant(rs, "occurred_at"), OutreachOutcome.valueOf(rs.getString("outcome"))));
                });
        return out;
    }

    public Map<UUID, List<InterventionPoint>> recentInterventionsByPatient(Collection<UUID> patientIds, Instant cutoff) {
        Map<UUID, List<InterventionPoint>> out = new LinkedHashMap<>();
        if (patientIds.isEmpty()) {
            return out;
        }
        var params = new MapSqlParameterSource("ids", patientIds).addValue("cutoff", Columns.ts(cutoff));
        jdbc.query("""
                select patient_id, occurred_at, intervention_type from clinical_interventions
                where patient_id in (:ids) and occurred_at >= :cutoff""",
                params, rs -> {
                    UUID pid = Columns.uuid(rs, "patient_id");
                    out.computeIfAbsent(pid, k -> new java.util.ArrayList<>()).add(new InterventionPoint(
                            Columns.instant(rs, "occurred_at"),
                            InterventionType.valueOf(rs.getString("intervention_type"))));
                });
        return out;
    }

    public Map<UUID, List<TaskPoint>> openContactTasksByPatient(Collection<UUID> patientIds) {
        Map<UUID, List<TaskPoint>> out = new LinkedHashMap<>();
        if (patientIds.isEmpty()) {
            return out;
        }
        jdbc.query("""
                select patient_id, type, due_at from tasks
                where patient_id in (:ids) and status in ('OPEN','IN_PROGRESS')
                  and type in ('REFILL_FOLLOW_UP','PATIENT_CONTACT')""",
                new MapSqlParameterSource("ids", patientIds), rs -> {
                    UUID pid = Columns.uuid(rs, "patient_id");
                    out.computeIfAbsent(pid, k -> new java.util.ArrayList<>()).add(new TaskPoint(
                            TaskType.valueOf(rs.getString("type")), Columns.instant(rs, "due_at")));
                });
        return out;
    }
}
