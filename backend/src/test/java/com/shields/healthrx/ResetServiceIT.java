package com.shields.healthrx;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shields.healthrx.domain.SystemActors;
import com.shields.healthrx.service.ResetService;

/** Verifies the one-click reset wipes mutated state and restores the deterministic seed + clock. */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class ResetServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    ResetService reset;
    @Autowired
    JdbcTemplate jdbc;

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void resetWipesMutationsAndRestoresSeedAndClock() {
        // Dirty the world: drop some data, advance/enable the clock, add an idempotency row.
        jdbc.update("delete from fills");
        jdbc.update("update simulation_state set enabled = true, current_instant = ? where id = 1",
                java.sql.Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
        jdbc.update("insert into processed_events (event_id, event_type, source, status, processed_at) "
                + "values (?, 'Test', 'test', 'APPLIED', now())", UUID.randomUUID());
        assertThat(count("fills")).isZero();

        reset.resetDemo();

        // Seed counts restored.
        assertThat(count("referrals")).isEqualTo(108);
        assertThat(count("patients")).isEqualTo(80);
        assertThat(count("therapies")).isEqualTo(42);
        assertThat(count("fills")).isGreaterThan(0);
        assertThat(count("processed_events")).isZero();
        // 8 seeded care team members + the System and Care Agent actors.
        assertThat(count("care_team_members")).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from care_team_members where id = ?",
                Integer.class, SystemActors.SYSTEM)).isEqualTo(1);

        // Clock reset + paused.
        assertThat(jdbc.queryForObject("select enabled from simulation_state where id = 1", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select current_instant from simulation_state where id = 1", OffsetDateTime.class)
                .toInstant()).isEqualTo(Instant.parse("2026-06-29T00:00:00Z"));

        // A fixed demo scenario is back.
        assertThat(jdbc.queryForObject("select count(*) from referrals where referral_number = 'RX-10003'",
                Integer.class)).isEqualTo(1);
    }
}
