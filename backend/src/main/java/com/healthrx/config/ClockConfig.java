package com.healthrx.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single source of "now" for the whole application.
 *
 * <p>Every metric calculation and every server-side default for an omitted {@code occurredAt}
 * or {@code changedAt} reads this {@link Clock}. By default it is pinned to a fixed demo
 * instant ({@code DEMO_NOW}) so relative metrics and the deterministic seed data stay
 * reproducible no matter when the demo is shown.
 *
 * <p>Override with {@code healthrx.clock.fixed-instant} (env {@code HEALTHRX_CLOCK_FIXED_INSTANT}).
 * Use the literal value {@code system} to fall back to the real wall clock (UTC).
 */
@Configuration
public class ClockConfig {

    /** Default pinned demo instant; matches the examples in the API contracts. */
    public static final String DEMO_NOW = "2026-06-29T00:00:00Z";

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    @Bean
    public Clock applicationClock(
            @Value("${healthrx.clock.fixed-instant:" + DEMO_NOW + "}") String fixedInstant) {
        if (fixedInstant == null || fixedInstant.isBlank() || "system".equalsIgnoreCase(fixedInstant.trim())) {
            log.info("HealthRx application clock: SYSTEM wall clock (UTC)");
            return Clock.systemUTC();
        }
        Instant instant = Instant.parse(fixedInstant.trim());
        log.info("HealthRx application clock: PINNED to {}", instant);
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
