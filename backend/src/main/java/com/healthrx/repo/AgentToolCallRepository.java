package com.healthrx.repo;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Apply-path idempotency ledger: each action tool executes at most once per recommendation.
 * {@link #claim} runs inside the tool's transaction so the ledger row and the domain write
 * commit together; a replayed call finds the row and returns the stored result instead of
 * re-executing. The MCP-path analogue of {@code processed_events}. Design §5.2.
 */
@Repository
public class AgentToolCallRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentToolCallRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if this call claimed the (recommendation, tool) slot; false on replay. */
    public boolean claim(UUID recommendationId, String toolName, String resultJson, Instant at) {
        int rows = jdbc.update("""
                insert into agent_tool_calls (recommendation_id, tool_name, result, created_at)
                values (:rec, :tool, cast(:result as jsonb), :at)
                on conflict (recommendation_id, tool_name) do nothing""",
                new MapSqlParameterSource()
                        .addValue("rec", recommendationId).addValue("tool", toolName)
                        .addValue("result", resultJson).addValue("at", Columns.ts(at)));
        return rows > 0;
    }

    public Optional<String> storedResult(UUID recommendationId, String toolName) {
        return jdbc.query("""
                select result::text as result from agent_tool_calls
                where recommendation_id = :rec and tool_name = :tool""",
                new MapSqlParameterSource().addValue("rec", recommendationId).addValue("tool", toolName),
                (rs, i) -> rs.getString("result")).stream().findFirst();
    }
}
