package com.shields.healthrx.repo;

import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Idempotency ledger for consumed events (apply each {@code eventId} at most once). */
@Repository
public class ProcessedEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ProcessedEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isProcessed(UUID eventId) {
        Long n = jdbc.queryForObject("select count(*) from processed_events where event_id = :id",
                new MapSqlParameterSource("id", eventId), Long.class);
        return n != null && n > 0;
    }

    /**
     * Atomically claims an event for processing. Returns {@code true} if this call inserted the
     * ledger row (caller should apply the event), {@code false} if it was already claimed.
     * Called inside the apply transaction so the claim and the domain write commit together —
     * a rollback (non-applicable event) releases the claim, and concurrent duplicate deliveries
     * are serialized by the {@code event_id} unique key.
     */
    public boolean claim(UUID eventId, String eventType, String source, Instant at) {
        int rows = jdbc.update("""
                insert into processed_events (event_id, event_type, source, status, processed_at)
                values (:id, :type, :source, 'APPLIED', :at)
                on conflict (event_id) do nothing""",
                new MapSqlParameterSource()
                        .addValue("id", eventId).addValue("type", eventType).addValue("source", source)
                        .addValue("at", Columns.ts(at)));
        return rows > 0;
    }
}
