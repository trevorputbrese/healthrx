package com.healthrx.agent.adherence.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.healthrx.agent.adherence.config.AgentProperties;
import com.healthrx.agent.adherence.mcp.McpSql;

/**
 * Run guards (phase-3-design.md §2 guardrail 2/3 and §6 run-loop step 2), all evaluated through
 * audited Postgres MCP reads: durable pause flag (fail-closed on a missing row), one live open
 * recommendation per patient (an APPLYING row, or a PENDING row that is still fresh), and a
 * sim-clock cooldown that is bypassed while the sim is paused.
 */
@Component
public class Guards {

    private static final Logger log = LoggerFactory.getLogger(Guards.class);

    private final McpSql sql;
    private final AgentProperties props;

    public Guards(McpSql sql, AgentProperties props) {
        this.sql = sql;
        this.props = props;
    }

    public record Decision(boolean proceed, String reason) {
        static Decision skip(String reason) {
            return new Decision(false, reason);
        }

        static Decision go(String reason) {
            return new Decision(true, reason);
        }
    }

    public Decision evaluate(UUID patientId) {
        Object paused = sql.scalar(
                "select paused from agent_control where agent_name = '" + props.name() + "' limit 1");
        if (paused == null || Boolean.parseBoolean(paused.toString())) {
            return Decision.skip(paused == null ? "no agent_control row (fail-closed)" : "agent paused");
        }

        List<Map<String, Object>> open = sql.query("""
                select id, status, created_at from agent_recommendations
                where agent_name = '%s' and patient_id = '%s' and status in ('PENDING', 'APPLYING')
                order by created_at desc limit 1""".formatted(props.name(), patientId));
        if (!open.isEmpty()) {
            Map<String, Object> row = open.get(0);
            String status = String.valueOf(row.get("status"));
            if ("APPLYING".equals(status)) {
                return Decision.skip("approval in flight for this patient");
            }
            Instant createdAt = parseTimestamp(String.valueOf(row.get("created_at")));
            if (createdAt == null || isFresh(patientId, createdAt)) {
                return Decision.skip("open PENDING recommendation is still fresh");
            }
            log.info("Stale PENDING {} for patient {} — proceeding; the new Created will supersede it",
                    row.get("id"), patientId);
            return Decision.go("supersede stale PENDING");
        }

        return cooldown(patientId);
    }

    /**
     * A PENDING recommendation is stale once the risk inputs it addressed have changed after its
     * creation: a later dispensed fill, a REACHED outreach, or a qualifying intervention (the
     * same inputs the refill-risk formula reads — metric-definitions.md).
     */
    private boolean isFresh(UUID patientId, Instant createdAt) {
        Object changed = sql.scalar("""
                select (exists (select 1 from fills where patient_id = '%1$s' and status = 'DISPENSED'
                                and created_at > timestamptz '%2$s')
                     or exists (select 1 from outreach_events where patient_id = '%1$s' and outcome = 'REACHED'
                                and created_at > timestamptz '%2$s')
                     or exists (select 1 from clinical_interventions where patient_id = '%1$s'
                                and intervention_type in ('ADHERENCE_COUNSELING', 'CARE_COORDINATION')
                                and created_at > timestamptz '%2$s')) as changed"""
                .formatted(patientId, createdAt));
        return changed == null || !Boolean.parseBoolean(changed.toString());
    }

    /** Sim-clock cooldown vs the last decided recommendation; bypassed while the sim is paused. */
    private Decision cooldown(UUID patientId) {
        List<Map<String, Object>> sim = sql.query(
                "select enabled, current_instant from simulation_state where id = 1");
        boolean simRunning = !sim.isEmpty() && Boolean.parseBoolean(String.valueOf(sim.get(0).get("enabled")));
        if (!simRunning) {
            return Decision.go("sim paused: time-based guards bypassed (presenter in control)");
        }
        Object last = sql.scalar("""
                select max(created_at) from agent_recommendations
                where agent_name = '%s' and patient_id = '%s'""".formatted(props.name(), patientId));
        if (last == null) {
            return Decision.go("no prior recommendation");
        }
        Instant simNow = parseTimestamp(String.valueOf(sim.get(0).get("current_instant")));
        Instant lastAt = parseTimestamp(String.valueOf(last));
        Duration cooldown = Duration.ofHours(props.cooldownSimHours());
        if (lastAt != null && simNow != null && Duration.between(lastAt, simNow).compareTo(cooldown) < 0) {
            return Decision.skip("cooldown: last recommendation " + lastAt + " within "
                    + props.cooldownSimHours() + " sim-hours of " + simNow);
        }
        return Decision.go("cooldown elapsed");
    }

    /** Waits until the API consumer has applied the trigger event (§6 run-loop step 3). */
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

    static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("-?\\d+")) {
            long epoch = Long.parseLong(trimmed);
            // Heuristic: values past ~2001-09 in millis are >= 1e12; smaller numbers are seconds.
            return epoch > 1_000_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        try {
            String iso = value.trim().replace(" ", "T");
            if (!iso.endsWith("Z") && !iso.contains("+")) {
                iso = iso + "Z";
            }
            return Instant.parse(iso);
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(value.trim().replace(" ", "T")).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    /** The shared simulated clock, read through the gateway (also stamps emitted events). */
    public Instant simNow() {
        Object v = sql.scalar("select current_instant from simulation_state where id = 1");
        Instant parsed = v == null ? null : parseTimestamp(String.valueOf(v));
        return parsed != null ? parsed : Instant.now();
    }
}
