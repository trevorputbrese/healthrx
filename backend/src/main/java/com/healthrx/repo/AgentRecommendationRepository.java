package com.healthrx.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Agent recommendation rows. Writes come from the event consumer (Created/Applied) and the
 * approve/dismiss flow; all state transitions are guarded UPDATEs so concurrent paths cannot
 * double-apply. See phase-3-design.md §6.
 */
@Repository
public class AgentRecommendationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentRecommendationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(
            UUID id, String agentName, UUID patientId, String patientName, UUID referralId,
            UUID therapyId, UUID taskId, UUID triggerEventId, String triggerEventType, String status,
            String summary, String recommendationJson, String traceJson, Instant createdAt,
            Instant decidedAt, UUID decidedById, String decidedByName) {
    }

    private static final String SELECT = """
            select r.id, r.agent_name, r.patient_id,
                   p.first_name || ' ' || p.last_name as patient_name,
                   r.referral_id, r.therapy_id, r.task_id, r.trigger_event_id, r.trigger_event_type,
                   r.status, r.summary, r.recommendation::text as recommendation_json,
                   r.trace::text as trace_json, r.created_at, r.decided_at, r.decided_by_id,
                   ct.display_name as decided_by_name
            from agent_recommendations r
            join patients p on p.id = r.patient_id
            left join care_team_members ct on ct.id = r.decided_by_id
            """;

    private static final RowMapper<Row> MAPPER = (rs, i) -> new Row(
            Columns.uuid(rs, "id"), rs.getString("agent_name"), Columns.uuid(rs, "patient_id"),
            rs.getString("patient_name"), Columns.uuid(rs, "referral_id"), Columns.uuid(rs, "therapy_id"),
            Columns.uuid(rs, "task_id"), Columns.uuid(rs, "trigger_event_id"), rs.getString("trigger_event_type"),
            rs.getString("status"), rs.getString("summary"), rs.getString("recommendation_json"),
            rs.getString("trace_json"), Columns.instant(rs, "created_at"), Columns.instant(rs, "decided_at"),
            Columns.uuid(rs, "decided_by_id"), rs.getString("decided_by_name"));

    /** Inserts from an AgentRecommendationCreated event; a duplicate id is an idempotent no-op. */
    public boolean insert(UUID id, String agentName, UUID patientId, UUID referralId, UUID therapyId,
            UUID taskId, UUID triggerEventId, String triggerEventType, String status, String summary,
            String recommendationJson, String traceJson, Instant createdAt) {
        int rows = jdbc.update("""
                insert into agent_recommendations
                    (id, agent_name, patient_id, referral_id, therapy_id, task_id, trigger_event_id,
                     trigger_event_type, status, summary, recommendation, trace, created_at)
                values (:id, :agent, :patient, :referral, :therapy, :task, :triggerId, :triggerType,
                        :status, :summary, cast(:rec as jsonb), cast(:trace as jsonb), :at)
                on conflict (id) do nothing""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("agent", agentName).addValue("patient", patientId)
                        .addValue("referral", referralId).addValue("therapy", therapyId).addValue("task", taskId)
                        .addValue("triggerId", triggerEventId).addValue("triggerType", triggerEventType)
                        .addValue("status", status).addValue("summary", summary)
                        .addValue("rec", recommendationJson)
                        .addValue("trace", traceJson == null ? "[]" : traceJson)
                        .addValue("at", Columns.ts(createdAt)));
        return rows > 0;
    }

    /** A newer recommendation supersedes any other open PENDING row for the same agent+patient. */
    public int supersedeOpenPending(String agentName, UUID patientId, UUID excludeId, Instant at) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'SUPERSEDED', decided_at = :at
                where agent_name = :agent and patient_id = :patient and status = 'PENDING'
                  and id <> :exclude""",
                new MapSqlParameterSource()
                        .addValue("agent", agentName).addValue("patient", patientId)
                        .addValue("exclude", excludeId).addValue("at", Columns.ts(at)));
    }

    /** AgentRecommendationApplied handler: a state repair, not a pure no-op (design §6). */
    public int applyRepair(UUID id, Instant occurredAt) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'APPLIED', decided_at = coalesce(decided_at, :at), applying_at = null
                where id = :id and status in ('PENDING', 'APPLYING')""",
                new MapSqlParameterSource().addValue("id", id).addValue("at", Columns.ts(occurredAt)));
    }

    /** Atomic approve gate: PENDING -> APPLYING, stamping the wall-clock applying_at. */
    public boolean gateApplying(UUID id, UUID decidedById, Instant wallNow) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'APPLYING', applying_at = :now, decided_by_id = :by
                where id = :id and status = 'PENDING'""",
                new MapSqlParameterSource().addValue("id", id).addValue("by", decidedById)
                        .addValue("now", Columns.ts(wallNow))) > 0;
    }

    public boolean markApplied(UUID id, Instant decidedAt) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'APPLIED', decided_at = :at, applying_at = null
                where id = :id and status = 'APPLYING'""",
                new MapSqlParameterSource().addValue("id", id).addValue("at", Columns.ts(decidedAt))) > 0;
    }

    /** Reverts a failed approve so a retry is possible; clears the tentative decided_by. */
    public boolean revertApplying(UUID id) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'PENDING', applying_at = null, decided_by_id = null
                where id = :id and status = 'APPLYING'""",
                new MapSqlParameterSource("id", id)) > 0;
    }

    public boolean dismiss(UUID id, UUID decidedById, Instant at) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'DISMISSED', decided_by_id = :by, decided_at = :at
                where id = :id and status = 'PENDING'""",
                new MapSqlParameterSource().addValue("id", id).addValue("by", decidedById)
                        .addValue("at", Columns.ts(at))) > 0;
    }

    /**
     * Lazily re-arms rows stuck APPLYING past the real-time threshold (API died mid-approve).
     * Safe because the apply tools are ledger-idempotent. Design §6 step 6.
     */
    public int rearmTimedOutApplying(Instant threshold) {
        return jdbc.update("""
                update agent_recommendations
                set status = 'PENDING', applying_at = null, decided_by_id = null
                where status = 'APPLYING' and applying_at < :threshold""",
                new MapSqlParameterSource("threshold", Columns.ts(threshold)));
    }

    public Optional<Row> find(UUID id) {
        return jdbc.query(SELECT + " where r.id = :id", new MapSqlParameterSource("id", id), MAPPER)
                .stream().findFirst();
    }

    public Optional<String> statusOf(UUID id) {
        return jdbc.query("select status from agent_recommendations where id = :id",
                new MapSqlParameterSource("id", id), (rs, i) -> rs.getString("status")).stream().findFirst();
    }

    public List<Row> page(String status, String agentName, int page, int size) {
        var params = new MapSqlParameterSource()
                .addValue("status", status).addValue("agent", agentName)
                .addValue("limit", size).addValue("offset", page * size);
        return jdbc.query(SELECT + """
                where (:status::text is null or r.status = :status)
                  and (:agent::text is null or r.agent_name = :agent)
                order by r.inserted_at desc, r.created_at desc, r.id
                limit :limit offset :offset""", params, MAPPER);
    }

    public long count(String status, String agentName) {
        Long n = jdbc.queryForObject("""
                select count(*) from agent_recommendations r
                where (:status::text is null or r.status = :status)
                  and (:agent::text is null or r.agent_name = :agent)""",
                new MapSqlParameterSource().addValue("status", status).addValue("agent", agentName), Long.class);
        return n == null ? 0 : n;
    }

    public int countPendingForPatient(UUID patientId) {
        Long n = jdbc.queryForObject(
                "select count(*) from agent_recommendations where patient_id = :id and status = 'PENDING'",
                new MapSqlParameterSource("id", patientId), Long.class);
        return n == null ? 0 : n.intValue();
    }

    public int countPendingForReferral(UUID referralId) {
        Long n = jdbc.queryForObject(
                "select count(*) from agent_recommendations where referral_id = :id and status = 'PENDING'",
                new MapSqlParameterSource("id", referralId), Long.class);
        return n == null ? 0 : n.intValue();
    }

    public record AgentStats(String agentName, long total, long pending, long applied, long autoApplied,
            Instant lastActivityAt) {
    }

    public Optional<AgentStats> stats(String agentName) {
        return jdbc.query("""
                select count(*) as total,
                       count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'APPLIED') as applied,
                       count(*) filter (where status = 'AUTO_APPLIED') as auto_applied,
                       max(created_at) as last_activity
                from agent_recommendations where agent_name = :agent""",
                new MapSqlParameterSource("agent", agentName), (rs, i) -> new AgentStats(
                        agentName, rs.getLong("total"), rs.getLong("pending"), rs.getLong("applied"),
                        rs.getLong("auto_applied"), Columns.instant(rs, "last_activity")))
                .stream().findFirst();
    }
}
