package com.shields.healthrx.repo;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Task writes for Phase 2 event consumption (RefillDue). */
@Repository
public class TaskRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TaskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
