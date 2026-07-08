package com.healthrx.agent.financial.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.healthrx.agent.financial.config.AgentProperties;
import com.healthrx.agent.financial.mcp.McpSql;

/**
 * Deterministic guard reads through audited Postgres MCP calls: the durable pause flag
 * (fail-closed), recommendation/referral existence checks, the wait-for-trigger-applied step,
 * and the shared simulated clock.
 */
@Component
public class Guards {

    private final McpSql sql;
    private final AgentProperties props;

    public Guards(McpSql sql, AgentProperties props) {
        this.sql = sql;
        this.props = props;
    }

    public boolean paused() {
        Object paused = sql.scalar(
                "select paused from agent_control where agent_name = '" + props.name() + "' limit 1");
        return paused == null || Boolean.parseBoolean(paused.toString()); // missing row = paused
    }

    /** True when a recommendation with this deterministic id already landed (run completed). */
    public boolean recommendationExists(UUID recommendationId) {
        Object n = sql.scalar(
                "select count(*) from agent_recommendations where id = '" + recommendationId + "'");
        return n != null && Long.parseLong(n.toString()) > 0;
    }

    /**
     * True when the referral row exists. A PriorAuthorizationApproved event can reference a
     * referral that no longer exists in rare edge cases (e.g. a demo reset mid-flight); acting
     * on a ghost would burn an LLM call and then fail the MCP action tool.
     */
    public boolean referralExists(UUID referralId) {
        Object n = sql.scalar("select count(*) from referrals where id = '" + referralId + "'");
        return n != null && Long.parseLong(n.toString()) > 0;
    }

    /** Waits until the API consumer has applied the trigger event. */
    public boolean waitForProcessed(UUID triggerEventId) {
        long deadline = System.nanoTime() + props.waitProcessedTimeoutMs() * 1_000_000L;
        while (System.nanoTime() < deadline) {
            Object n = sql.scalar(
                    "select count(*) from processed_events where event_id = '" + triggerEventId + "'");
            if (n != null && Long.parseLong(n.toString()) > 0) {
                return true;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public record SimClock(boolean enabled, Instant now) {
    }

    public SimClock simClock() {
        List<Map<String, Object>> rows = sql.query(
                "select enabled, current_instant from simulation_state where id = 1");
        if (rows.isEmpty()) {
            return new SimClock(false, Instant.now());
        }
        Instant now = parseTimestamp(String.valueOf(rows.get(0).get("current_instant")));
        boolean enabled = Boolean.parseBoolean(String.valueOf(rows.get(0).get("enabled")));
        return new SimClock(enabled, now != null ? now : Instant.now());
    }

    static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("-?\\d+")) {
            long epoch = Long.parseLong(trimmed);
            // The Postgres MCP server serializes timestamptz as epoch millis on the wire.
            return epoch > 1_000_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        try {
            String iso = trimmed.replace(" ", "T");
            if (!iso.endsWith("Z") && !iso.contains("+")) {
                iso = iso + "Z";
            }
            return Instant.parse(iso);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(trimmed.replace(" ", "T")).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
