package com.shields.healthrx.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Task writes (Phase 2 RefillDue, agent create_task) and the Tasks-page reads. */
@Repository
public class TaskRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TaskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TaskRow(
            UUID id, String type, String status, String priority, String title, String description,
            Instant dueAt, Instant completedAt, Instant createdAt,
            UUID patientId, String patientName, UUID referralId, String referralNumber,
            UUID ownerId, String ownerName) {
    }

    private static final String SELECT = """
            select t.id, t.type, t.status, t.priority, t.title, t.description,
                   t.due_at, t.completed_at, t.created_at,
                   p.id as patient_id, p.first_name || ' ' || p.last_name as patient_name,
                   r.id as referral_id, r.referral_number,
                   ct.id as owner_id, ct.display_name as owner_name
            from tasks t
            join patients p on p.id = t.patient_id
            left join referrals r on r.id = t.referral_id
            join care_team_members ct on ct.id = t.owner_id
            """;

    private static final RowMapper<TaskRow> MAPPER = (rs, i) -> new TaskRow(
            Columns.uuid(rs, "id"), rs.getString("type"), rs.getString("status"),
            rs.getString("priority"), rs.getString("title"), rs.getString("description"),
            Columns.instant(rs, "due_at"), Columns.instant(rs, "completed_at"),
            Columns.instant(rs, "created_at"),
            Columns.uuid(rs, "patient_id"), rs.getString("patient_name"),
            Columns.uuid(rs, "referral_id"), rs.getString("referral_number"),
            Columns.uuid(rs, "owner_id"), rs.getString("owner_name"));

    public List<TaskRow> page(String status, String search, int page, int size) {
        StringBuilder sql = new StringBuilder(SELECT);
        MapSqlParameterSource params = new MapSqlParameterSource();
        sql.append(where(status, search, params));
        sql.append(" order by t.created_at desc, t.id limit :limit offset :offset");
        params.addValue("limit", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    public long count(String status, String search) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        Long n = jdbc.queryForObject("select count(*) from tasks t join patients p on p.id = t.patient_id"
                + " left join referrals r on r.id = t.referral_id " + where(status, search, params),
                params, Long.class);
        return n == null ? 0 : n;
    }

    private static String where(String status, String search, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" where 1=1");
        if (status != null && !status.isBlank()) {
            if ("OPEN_ALL".equals(status)) {
                sql.append(" and t.status in ('OPEN', 'IN_PROGRESS')");
            } else {
                sql.append(" and t.status = :status");
                params.addValue("status", status);
            }
        }
        if (search != null && !search.isBlank()) {
            sql.append(" and (t.title ilike :search"
                    + " or p.first_name || ' ' || p.last_name ilike :search"
                    + " or r.referral_number ilike :search)");
            params.addValue("search", "%" + search.trim() + "%");
        }
        return sql.toString();
    }

    public Optional<TaskRow> find(UUID id) {
        return jdbc.query(SELECT + " where t.id = :id", new MapSqlParameterSource("id", id), MAPPER)
                .stream().findFirst();
    }

    /** Moves the task to the new status, stamping/clearing completed_at as appropriate. */
    public void updateStatus(UUID id, String status, Instant completedAt) {
        jdbc.update("update tasks set status = :status, completed_at = :completed where id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("status", status)
                        .addValue("completed", Columns.ts(completedAt)));
    }

    public void insert(UUID id, UUID patientId, UUID referralId, UUID ownerId, String type, String status,
            String priority, String title, String description, Instant dueAt, Instant createdAt) {
        jdbc.update("""
                insert into tasks (id, patient_id, referral_id, owner_id, type, status, priority,
                                   title, description, due_at, created_at)
                values (:id, :pid, :rid, :owner, :type, :status, :priority, :title, :desc, :due, :created)""",
                new MapSqlParameterSource()
                        .addValue("id", id).addValue("pid", patientId).addValue("rid", referralId)
                        .addValue("owner", ownerId).addValue("type", type).addValue("status", status)
                        .addValue("priority", priority).addValue("title", title).addValue("desc", description)
                        .addValue("due", Columns.ts(dueAt)).addValue("created", Columns.ts(createdAt)));
    }
}
