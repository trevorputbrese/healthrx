package com.healthrx.agent.financial.core;

import java.util.ArrayDeque;
import java.util.Deque;

import org.springframework.stereotype.Component;

import com.healthrx.agent.financial.config.AgentProperties;

/** Real-time run rate cap. Sliding one-minute window over the agent's runs. */
@Component
public class RateLimiter {

    private final Deque<Long> runs = new ArrayDeque<>();
    private final int perMinute;

    public RateLimiter(AgentProperties props) {
        this.perMinute = Math.max(1, props.ratePerMinute());
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
