package com.shields.healthrx.generator.sim;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and advances the shared simulated clock (the {@code simulation_state} row). */
@Repository
public class SimulationStateRepository {

    private final JdbcTemplate jdbc;

    public SimulationStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record State(boolean enabled, Instant currentInstant, int speedSecondsPerSecond,
            boolean ambientEnabled, Instant updatedAt) {
    }

    public State read() {
        return jdbc.queryForObject("""
                select enabled, current_instant, speed_seconds_per_second, ambient_enabled, updated_at
                from simulation_state where id = 1""",
                (rs, i) -> new State(
                        rs.getBoolean("enabled"),
                        rs.getObject("current_instant", OffsetDateTime.class).toInstant(),
                        rs.getInt("speed_seconds_per_second"),
                        rs.getBoolean("ambient_enabled"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()));
    }

    public void advance(Instant newInstant, Instant updatedAt) {
        jdbc.update("update simulation_state set current_instant = ?, updated_at = ? where id = 1",
                OffsetDateTime.ofInstant(newInstant, ZoneOffset.UTC), OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
    }

    public void setEnabled(boolean enabled, Instant updatedAt) {
        jdbc.update("update simulation_state set enabled = ?, updated_at = ? where id = 1",
                enabled, OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
    }

    public void setSpeed(int speedSecondsPerSecond, Instant updatedAt) {
        jdbc.update("update simulation_state set speed_seconds_per_second = ?, updated_at = ? where id = 1",
                speedSecondsPerSecond, OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
    }

    public void setAmbientEnabled(boolean ambientEnabled, Instant updatedAt) {
        jdbc.update("update simulation_state set ambient_enabled = ?, updated_at = ? where id = 1",
                ambientEnabled, OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
    }
}
