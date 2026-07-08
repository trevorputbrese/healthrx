package com.healthrx.generator.sim;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.healthrx.generator.sim.SimulationStateRepository.State;

/**
 * Owns advancing the shared simulated clock. Each tick advances {@code current_instant} by
 * {@code speed} simulated seconds (per real tick) when the simulation is enabled. The HealthRx
 * API reads the same row for its metric "now".
 */
@Service
public class SimulationClock {

    private final SimulationStateRepository repo;

    public SimulationClock(SimulationStateRepository repo) {
        this.repo = repo;
    }

    public State state() {
        return repo.read();
    }

    /** Advances simulated time for one tick and returns the new instant (or current if disabled). */
    public Instant advanceTick() {
        State s = repo.read();
        if (!s.enabled()) {
            return s.currentInstant();
        }
        Instant next = s.currentInstant().plusSeconds(s.speedSecondsPerSecond());
        // A concurrent pause/reset between the read and the write makes the advance a no-op.
        return repo.advance(next, Instant.now()) ? next : s.currentInstant();
    }

    public Instant now() {
        return repo.read().currentInstant();
    }

    public boolean enabled() {
        return repo.read().enabled();
    }

    public void start() {
        repo.setEnabled(true, Instant.now());
    }

    public void stop() {
        repo.setEnabled(false, Instant.now());
    }

    public void setSpeed(int speedSecondsPerSecond) {
        repo.setSpeed(Math.max(1, speedSecondsPerSecond), Instant.now());
    }

    /** Whether the ambient trickle (random referral/refill/engagement events) is emitting. */
    public boolean ambientEnabled() {
        return repo.read().ambientEnabled();
    }

    public void setAmbientEnabled(boolean ambientEnabled) {
        repo.setAmbientEnabled(ambientEnabled, Instant.now());
    }
}
