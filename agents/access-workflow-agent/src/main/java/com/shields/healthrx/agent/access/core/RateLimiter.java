package com.shields.healthrx.agent.access.core;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.shields.healthrx.agent.access.config.AgentProperties;

/**
 * Real-time LLM-run rate cap (as-built delta from the design's "per sim-hour" wording: at
 * fast-forward speeds a sim-hour elapses in milliseconds, so the cap that actually protects the
 * LLM budget is wall-clock). Sliding one-minute window over both triage and scan runs. The
 * payer-check path constructs its own instance with its own cap via the int constructor.
 */
@Component
public class RateLimiter {

    private final Deque<Long> runs = new ArrayDeque<>();
    private final int perMinute;

    @Autowired
    public RateLimiter(AgentProperties props) {
        this(props.ratePerMinute());
    }

    public RateLimiter(int perMinute) {
        this.perMinute = Math.max(1, perMinute);
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        while (!runs.isEmpty() && now - runs.peekFirst() > 60_000_000_000L) {
            runs.pollFirst();
        }
        if (runs.size() >= perMinute) {
            return false;
        }
        runs.addLast(now);
        return true;
    }
}
