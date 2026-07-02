package com.shields.healthrx.agent.access.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.shields.healthrx.agent.access.config.AgentProperties;
import com.shields.healthrx.agent.access.mcp.McpSql;

/**
 * Deterministic guard reads through audited Postgres MCP calls (phase-3-design.md §6): the durable
 * pause flag (fail-closed), open-agent-task/dedup checks, the wait-for-trigger-applied step, and
 * the shared simulated clock.
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

    /** True when the deterministic Created event for this run was already consumed. */
    public boolean createdEventProcessed(UUID recommendationId) {
        Object n = sql.scalar("select count(*) from processed_events where event_id = '"
                + AgentIds.createdEventId(recommendationId) + "'");
        return n != null && Long.parseLong(n.toString()) > 0;
    }

    /** Open [Agent] follow-up task already routed for this referral? (one-open-task guard) */
    public boolean openAgentTask(UUID referralId) {
        Object n = sql.scalar("""
                select count(*) from tasks
                where referral_id = '%s' and type = 'ACCESS_FOLLOW_UP'
                  and status in ('OPEN', 'IN_PROGRESS')""".formatted(referralId));
        return n != null && Long.parseLong(n.toString()) > 0;
    }

    /**
     * Emit-repair lookup (§6 Access run-loop step 2): the ledger row proves create_task ran for a
     * recommendation whose Created event may never have been emitted (crash window).
     */
    public List<Map<String, Object>> taskLedgerForReferral(UUID referralId) {
        return sql.query("""
                select atc.recommendation_id, atc.result::text as result, t.title, t.description,
                       t.patient_id
                from agent_tool_calls atc
                join tasks t on t.id = (atc.result->>'taskId')::uuid
                where atc.tool_name = 'create_task' and t.referral_id = '%s'
                  and t.status in ('OPEN', 'IN_PROGRESS')""".formatted(referralId));
    }

    /** Decided (dismissed/superseded) recommendation count for a referral — feeds episode ids. */
    public long decidedCountForReferral(UUID referralId) {
        Object n = sql.scalar("""
                select count(*) from agent_recommendations
                where agent_name = '%s' and referral_id = '%s'
                  and status in ('DISMISSED', 'SUPERSEDED')""".formatted(props.name(), referralId));
        return n == null ? 0 : Long.parseLong(n.toString());
    }

    /** Waits until the API consumer has applied the trigger event (§6 run-loop). */
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
