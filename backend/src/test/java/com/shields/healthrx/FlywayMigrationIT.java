package com.shields.healthrx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.shields.healthrx.repo.QueueFilter;
import com.shields.healthrx.service.DashboardService;
import com.shields.healthrx.service.ReferralService;
import com.shields.healthrx.repo.DashboardFilter;
import com.shields.healthrx.config.AppTime;

/**
 * Boots the full application against a throwaway Postgres (Testcontainers), proving Flyway
 * applies the full migration chain against a blank database, the deterministic seed loads (as
 * trimmed by V13 — 14 curated referrals, one per disease/lifecycle-status combination plus the
 * 4 scripted scenario referrals), and the read/metric stack works against real data. Skipped
 * automatically when no container runtime is available.
 */
@SpringBootTest(properties = {
        "healthrx.events.consumer.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"
})
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ReferralService referralService;
    @Autowired
    DashboardService dashboardService;
    @Autowired
    AppTime time;

    @Test
    void migrationsApplyAndSeedLoads() {
        Integer migrations = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(2);

        // V13 trims the seeded referral set to 14 (one per disease/status combination plus the
        // 4 scripted scenario referrals); patients are untouched, and therapies/fills follow the
        // ACTIVE_THERAPY referrals 1:1.
        assertThat(jdbc.queryForObject("select count(*) from referrals", Integer.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("select count(*) from patients", Integer.class)).isEqualTo(80);
        assertThat(jdbc.queryForObject("select count(*) from therapies", Integer.class)).isEqualTo(4);

        // Demo scenario rows are present and recognizable.
        assertThat(jdbc.queryForObject(
                "select count(*) from referrals where referral_number = 'RX-10003'", Integer.class)).isEqualTo(1);
    }

    @Test
    void readStackWorksAgainstRealData() {
        var page = referralService.queue(new QueueFilter(null, null, null, null, null, null, null, null,
                false, 0, 25, "receivedAt", "desc"));
        assertThat(page.totalItems()).isGreaterThan(0);
        assertThat(page.items()).isNotEmpty();

        var summary = dashboardService.summary(new DashboardFilter(
                time.today().minusDays(30), time.today(), null, null, null, null, null));
        assertThat(summary.tiles().activePatientsOnTherapy()).isEqualTo(4);
    }
}
