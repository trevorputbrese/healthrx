package com.healthrx.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.healthrx.repo.SimulationStateRepository;
import com.healthrx.repo.SimulationStateRepository.State;

/**
 * The single accessor for "now" / "today".
 *
 * <p>"Now" is the shared simulated instant ({@code simulation_state.current_instant}) — which is
 * seeded to the pinned demo anchor and advances while the generator runs, and <em>freezes</em>
 * when paused (so a paused demo holds its current simulated time rather than reverting). The
 * {@code enabled} flag only governs whether the generator advances the clock, not what the API
 * reads. Falls back to the pinned {@link Clock} only if the row is missing. Read at most once per
 * second to keep metric calls cheap.
 */
@Component
public class AppTime {

    private static final long CACHE_TTL_NANOS = 1_000_000_000L; // 1s

    private final Clock clock;
    private final SimulationStateRepository simulation;

    private volatile State cached;
    private volatile long cachedAtNanos;

    public AppTime(Clock clock, SimulationStateRepository simulation) {
        this.clock = clock;
        this.simulation = simulation;
    }

    public Instant now() {
        State s = currentState();
        return (s != null && s.currentInstant() != null) ? s.currentInstant() : clock.instant();
    }

    public LocalDate today() {
        return LocalDate.ofInstant(now(), ZoneOffset.UTC);
    }

    private State currentState() {
        long nowNanos = System.nanoTime();
        State c = cached;
        if (c == null || nowNanos - cachedAtNanos > CACHE_TTL_NANOS) {
            c = simulation.read().orElse(null);
            cached = c;
            cachedAtNanos = nowNanos;
        }
        return c;
    }
}
