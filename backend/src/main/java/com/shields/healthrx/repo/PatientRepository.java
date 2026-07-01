package com.shields.healthrx.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.shields.healthrx.domain.OutreachChannel;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.domain.TaskType;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.CommonDtos.TaskSummary;
import com.shields.healthrx.web.dto.PatientDtos.InterventionItem;
import com.shields.healthrx.web.dto.PatientDtos.OutreachItem;
import com.shields.healthrx.web.dto.TimelineDtos;

/** Patient header, workbench lists, timeline sources, and outreach/intervention writes. */
@Repository
public class PatientRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PatientRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record HeaderRow(
            UUID id, String demoMrn, String displayName, LocalDate dateOfBirth, String diseaseState,
            UUID clinicId, String clinicName, UUID payerId, String payerName, UUID ownerId, String ownerName) {
    }

    public boolean exists(UUID id) {
        Long n = jdbc.queryForObject("select count(*) from patients where id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        return n != null && n > 0;
    }

    public Optional<HeaderRow> findHeader(UUID id) {
        var rows = jdbc.query("""
                select p.id, p.demo_mrn, p.first_name || ' ' || p.last_name as display_name,
                       p.date_of_birth, p.disease_state, c.id as clinic_id, c.name as clinic_name,
                       pay.id as payer_id, pay.name as payer_name, ct.id as owner_id, ct.display_name as owner_name
                from patients p
                join clinics c on c.id = p.clinic_id
                join payers pay on pay.id = p.payer_id
                join care_team_members ct on ct.id = p.primary_owner_id
                where p.id = :id""",
                new MapSqlParameterSource("id", id), (rs, i) -> new HeaderRow(
                        Columns.uuid(rs, "id"), rs.getString("demo_mrn"), rs.getString("display_name"),
                        Columns.localDate(rs, "date_of_birth"), rs.getString("disease_state"),
                        Columns.uuid(rs, "clinic_id"), rs.getString("clinic_name"),
                        Columns.uuid(rs, "payer_id"), rs.getString("payer_name"),
                        Columns.uuid(rs, "owner_id"), rs.getString("owner_name")));
        return rows.stream().findFirst();
    }

    public List<TaskSummary> openTasksForPatient(UUID patientId) {
        return jdbc.query("""
                select id, type, status, priority, title, due_at from tasks
                where patient_id = :id and status in ('OPEN','IN_PROGRESS')
                order by due_at asc nulls last""",
                new MapSqlParameterSource("id", patientId), ReferralRepository::taskSummary);
    }

    public List<OutreachItem> recentOutreach(UUID patientId, int limit) {
        var params = new MapSqlParameterSource("id", patientId).addValue("limit", limit);
        return jdbc.query("""
                select o.id, o.referral_id, o.channel, o.outcome, o.occurred_at, o.notes,
                       ct.id as owner_id, ct.display_name as owner_name
                from outreach_events o join care_team_members ct on ct.id = o.owner_id
                where o.patient_id = :id order by o.occurred_at desc limit :limit""",
                params, (rs, i) -> new OutreachItem(
                        Columns.uuid(rs, "id"), Columns.uuid(rs, "referral_id"),
                        new NamedRef(Columns.uuid(rs, "owner_id"), rs.getString("owner_name")),
                        rs.getString("channel"), rs.getString("outcome"),
                        Columns.instant(rs, "occurred_at"), rs.getString("notes")));
    }

    public List<InterventionItem> recentInterventions(UUID patientId, int limit) {
        var params = new MapSqlParameterSource("id", patientId).addValue("limit", limit);
        return jdbc.query("""
                select ci.id, ci.referral_id, ci.intervention_type, ci.summary, ci.occurred_at,
                       ct.id as owner_id, ct.display_name as owner_name
                from clinical_interventions ci join care_team_members ct on ct.id = ci.owner_id
                where ci.patient_id = :id order by ci.occurred_at desc limit :limit""",
                params, (rs, i) -> new InterventionItem(
                        Columns.uuid(rs, "id"), Columns.uuid(rs, "referral_id"),
                        new NamedRef(Columns.uuid(rs, "owner_id"), rs.getString("owner_name")),
                        rs.getString("intervention_type"), rs.getString("summary"),
                        Columns.instant(rs, "occurred_at")));
    }

    // --- Writes ---

    public void insertOutreach(UUID id, UUID patientId, UUID referralId, UUID ownerId,
            OutreachChannel channel, OutreachOutcome outcome, Instant occurredAt, String notes, Instant createdAt) {
        jdbc.update("""
                insert into outreach_events
                    (id, patient_id, referral_id, owner_id, channel, outcome, notes, occurred_at, created_at)
                values (:id, :pid, :rid, :owner, :channel, :outcome, :notes, :occurred, :created)""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("pid", patientId).addValue("rid", referralId)
                        .addValue("owner", ownerId).addValue("channel", channel.name())
                        .addValue("outcome", outcome.name()).addValue("notes", notes)
                        .addValue("occurred", Columns.ts(occurredAt)).addValue("created", Columns.ts(createdAt)));
    }

    public void insertIntervention(UUID id, UUID patientId, UUID referralId, UUID ownerId,
            String interventionType, String summary, Instant occurredAt, Instant createdAt) {
        jdbc.update("""
                insert into clinical_interventions
                    (id, patient_id, referral_id, owner_id, intervention_type, summary, occurred_at, created_at)
                values (:id, :pid, :rid, :owner, :type, :summary, :occurred, :created)""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("pid", patientId).addValue("rid", referralId)
                        .addValue("owner", ownerId).addValue("type", interventionType)
                        .addValue("summary", summary).addValue("occurred", Columns.ts(occurredAt))
                        .addValue("created", Columns.ts(createdAt)));
    }

    // --- Timeline sources (each returns ready-to-merge items) ---

    public List<TimelineDtos.Item> timelineStatusHistory(UUID patientId) {
        return jdbc.query("""
                select h.id, h.from_status, h.to_status, h.changed_at, h.note, h.phase2_event_type,
                       ct.id as actor_id, ct.display_name as actor_name
                from referral_status_history h
                join referrals r on r.id = h.referral_id
                left join care_team_members ct on ct.id = h.changed_by_id
                where r.patient_id = :id""",
                new MapSqlParameterSource("id", patientId), (rs, i) -> {
                    String from = rs.getString("from_status");
                    String to = rs.getString("to_status");
                    NamedRef actor = actor(rs);
                    Map<String, Object> meta = new LinkedHashMap<>();
                    String type;
                    String title;
                    if (from == null) {
                        type = "REFERRAL";
                        title = "Referral created";
                    } else if (from.equals(to)) {
                        type = "FINANCIAL";
                        title = "Financial details updated";
                    } else {
                        type = "STATUS_CHANGE";
                        title = ReferralStatus.valueOf(to).label();
                        meta.put("fromStatus", from);
                        meta.put("toStatus", to);
                    }
                    return new TimelineDtos.Item(Columns.uuid(rs, "id"), type, Columns.instant(rs, "changed_at"),
                            title, rs.getString("note"), actor, meta);
                });
    }

    public List<TimelineDtos.Item> timelineFills(UUID patientId) {
        return jdbc.query("""
                select f.id, f.fill_number, f.status, f.dispensed_at, f.expected_refill_date,
                       coalesce(f.dispensed_at::timestamptz, f.created_at) as occurred_at, m.name as medication_name
                from fills f
                join therapies t on t.id = f.therapy_id
                join medications m on m.id = t.medication_id
                where f.patient_id = :id""",
                new MapSqlParameterSource("id", patientId), (rs, i) -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("fillNumber", rs.getInt("fill_number"));
                    meta.put("status", rs.getString("status"));
                    String title = "Fill #" + rs.getInt("fill_number") + " " + rs.getString("status").toLowerCase();
                    return new TimelineDtos.Item(Columns.uuid(rs, "id"), "FILL",
                            Columns.instant(rs, "occurred_at"), title, rs.getString("medication_name"), null, meta);
                });
    }

    public List<TimelineDtos.Item> timelineTasks(UUID patientId) {
        return jdbc.query("""
                select id, type, status, title, created_at from tasks where patient_id = :id""",
                new MapSqlParameterSource("id", patientId), (rs, i) -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("type", rs.getString("type"));
                    meta.put("status", rs.getString("status"));
                    return new TimelineDtos.Item(Columns.uuid(rs, "id"), "TASK",
                            Columns.instant(rs, "created_at"),
                            TaskType.valueOf(rs.getString("type")).label() + " task", rs.getString("title"),
                            null, meta);
                });
    }

    public List<TimelineDtos.Item> timelineOutreach(UUID patientId) {
        return jdbc.query("""
                select o.id, o.channel, o.outcome, o.occurred_at, o.notes,
                       ct.id as actor_id, ct.display_name as actor_name
                from outreach_events o left join care_team_members ct on ct.id = o.owner_id
                where o.patient_id = :id""",
                new MapSqlParameterSource("id", patientId), (rs, i) -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("channel", rs.getString("channel"));
                    meta.put("outcome", rs.getString("outcome"));
                    String title = OutreachChannel.valueOf(rs.getString("channel")).label() + " outreach logged";
                    return new TimelineDtos.Item(Columns.uuid(rs, "id"), "OUTREACH",
                            Columns.instant(rs, "occurred_at"), title, rs.getString("notes"), actor(rs), meta);
                });
    }

    public List<TimelineDtos.Item> timelineInterventions(UUID patientId) {
        return jdbc.query("""
                select ci.id, ci.intervention_type, ci.summary, ci.occurred_at,
                       ct.id as actor_id, ct.display_name as actor_name
                from clinical_interventions ci left join care_team_members ct on ct.id = ci.owner_id
                where ci.patient_id = :id""",
                new MapSqlParameterSource("id", patientId), (rs, i) -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("interventionType", rs.getString("intervention_type"));
                    return new TimelineDtos.Item(Columns.uuid(rs, "id"), "INTERVENTION",
                            Columns.instant(rs, "occurred_at"), "Clinical intervention logged",
                            rs.getString("summary"), actor(rs), meta);
                });
    }

    public List<TimelineDtos.Item> timelineNotes(UUID patientId) {
        return jdbc.query("""
                select n.id, n.body, n.created_at, ct.id as actor_id, ct.display_name as actor_name
                from referral_notes n
                join referrals r on r.id = n.referral_id
                join care_team_members ct on ct.id = n.author_id
                where r.patient_id = :id""",
                new MapSqlParameterSource("id", patientId), (rs, i) -> new TimelineDtos.Item(
                        Columns.uuid(rs, "id"), "NOTE", Columns.instant(rs, "created_at"),
                        "Note added", rs.getString("body"), actor(rs), new LinkedHashMap<>()));
    }

    private static NamedRef actor(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID actorId = Columns.uuid(rs, "actor_id");
        return actorId == null ? null : new NamedRef(actorId, rs.getString("actor_name"));
    }
}
