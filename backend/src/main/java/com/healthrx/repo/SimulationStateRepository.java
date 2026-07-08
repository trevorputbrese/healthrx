package com.healthrx.repo;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the shared simulated-clock state (the API only reads; the generator advances it). */
@Repository
public class SimulationStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SimulationStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record State(boolean enabled, Instant currentInstant, int speedSecondsPerSecond, Instant updatedAt) {
    }

    public Optional<State> read() {
        var rows = jdbc.query(
                "select enabled, current_instant, speed_seconds_per_second, updated_at "
                        + "from simulation_state where id = 1",
                new MapSqlParameterSource(),
                (rs, i) -> new State(
                        rs.getBoolean("enabled"),
                        Columns.instant(rs, "current_instant"),
                        rs.getInt("speed_seconds_per_second"),
                        Columns.instant(rs, "updated_at")));
        return rows.stream().findFirst();
    }
}
