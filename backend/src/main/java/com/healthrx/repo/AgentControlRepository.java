package com.healthrx.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable per-agent kill switch (design §2 guardrail 3). The API upserts; agents read the row
 * via Postgres MCP per trigger and treat a missing row as paused (fail-closed).
 */
@Repository
public class AgentControlRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentControlRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Control(String agentName, boolean paused, Instant updatedAt) {
    }

    public Optional<Control> find(String agentName) {
        return jdbc.query("select agent_name, paused, updated_at from agent_control where agent_name = :name",
                new MapSqlParameterSource("name", agentName), (rs, i) -> new Control(
                        rs.getString("agent_name"), rs.getBoolean("paused"), Columns.instant(rs, "updated_at")))
                .stream().findFirst();
    }

    public List<Control> all() {
        return jdbc.query("select agent_name, paused, updated_at from agent_control order by agent_name",
                (rs, i) -> new Control(rs.getString("agent_name"), rs.getBoolean("paused"),
                        Columns.instant(rs, "updated_at")));
    }

    public void upsert(String agentName, boolean paused, Instant at) {
        jdbc.update("""
                insert into agent_control (agent_name, paused, updated_at)
                values (:name, :paused, :at)
                on conflict (agent_name) do update set paused = :paused, updated_at = :at""",
                new MapSqlParameterSource()
                        .addValue("name", agentName).addValue("paused", paused).addValue("at", Columns.ts(at)));
    }
}
