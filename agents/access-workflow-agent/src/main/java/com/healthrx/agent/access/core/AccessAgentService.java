package com.healthrx.agent.access.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrx.agent.access.config.AgentProperties;
import com.healthrx.agent.access.core.TriageModels.Triage;
import com.healthrx.agent.access.llm.TriageEngine;
import com.healthrx.agent.access.mcp.McpSql;
import com.healthrx.agent.access.messaging.EventEnvelope;
import com.healthrx.agent.access.messaging.EventPublisher;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The autonomous run loop (phase-3-design.md §6, Access): guards -> triage via the LLM and
 * gateway tools -> create_task through the HealthRx MCP action server (ledger-idempotent) ->
 * emit AgentRecommendationCreated born AUTO_APPLIED. Shared by the ReferralCreated listener and
 * the stuck-referral scan. Includes the emit-repair path for the crash window between the task
 * write and the event emission ("Nothing invisible", §2 guardrail 4).
 */
@Service
public class AccessAgentService {

    private static final Logger log = LoggerFactory.getLogger(AccessAgentService.class);

    private final Guards guards;
    private final RateLimiter rate;
    private final TriageEngine engine;
    private final EventPublisher publisher;
    private final McpSyncClient healthrx;
    private final McpSql sql;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final Set<UUID> inFlightReferrals = ConcurrentHashMap.newKeySet();

    public AccessAgentService(Guards guards, RateLimiter rate, TriageEngine engine, EventPublisher publisher,
            McpSyncClient healthrxMcp, McpSql sql, AgentProperties props, ObjectMapper mapper) {
        this.guards = guards;
        this.rate = rate;
        this.engine = engine;
        this.publisher = publisher;
        this.healthrx = healthrxMcp;
        this.sql = sql;
        this.props = props;
        this.mapper = mapper;
    }

    /** Event-triggered triage of a brand-new referral. */
    public void onReferralCreated(EventEnvelope env) {
        Map<String, Object> p = env.payload() == null ? Map.of() : env.payload();
        UUID referralId = uuid(p, "referralId");
        UUID patientId = uuid(p, "patientId");
        if (referralId == null || patientId == null) {
            log.warn("ReferralCreated without referralId/patientId — skipping. eventId={}", env.eventId());
            return;
        }
        UUID recommendationId = AgentIds.recommendationId(props.name(), env.eventId());
        run(recommendationId, env.eventId(), env.eventType(), referralId, patientId,
                "new referral triage", () -> guards.waitForProcessed(env.eventId()));
    }

    /** Scan-triggered triage of a stuck referral (episode identity supplied by the scanner). */
    public void onStuckEpisode(UUID episodeId, UUID referralId, UUID patientId, String rule) {
        UUID recommendationId = AgentIds.recommendationId(props.name(), episodeId);
        run(recommendationId, episodeId, "StuckReferralScan/" + rule, referralId, patientId,
                "stuck referral: " + rule, () -> true);
    }

    private void run(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId,
            UUID patientId, String reason, java.util.function.BooleanSupplier waitStep) {
        if (!inFlightReferrals.add(referralId)) {
            log.info("Run already in flight for referral {} — skipping", referralId);
            return;
        }
        try {
            if (guards.paused()) {
                log.info("Agent paused — skipping {}", referralId);
                return;
            }
            if (guards.recommendationExists(recommendationId)) {
                log.info("Recommendation {} already recorded — nothing to do", recommendationId);
                return;
            }
            if (guards.openAgentTask(referralId)) {
                repairOrSkip(referralId);
                return;
            }
            if (!rate.tryAcquire()) {
                log.warn("Rate cap hit — skipping triage for referral {}", referralId);
                return;
            }
            if (!waitStep.getAsBoolean()) {
                log.warn("Trigger {} not visible in processed_events within timeout — proceeding", triggerId);
            }
            if (!guards.referralExists(referralId)) {
                log.info("Referral {} does not exist (duplicate-skip or not yet applied) — no triage",
                        referralId);
                return;
            }

            TraceRecorder trace = new TraceRecorder();
            trace.step("trigger", reason + " (referral " + referralId + ")");

            Triage triage = engine.triage(referralId, patientId, reason, trace);
            trace.step("proposal", triage.summary() != null ? triage.summary() : "triage drafted");

            String title = triage.taskTitle() != null && !triage.taskTitle().isBlank()
                    ? triage.taskTitle() : "Follow up on stuck referral";
            String description = (triage.summary() == null ? "" : triage.summary())
                    + "\n\nRecommended next action: " + (triage.nextAction() == null ? "review the case"
                    : triage.nextAction());

            String taskResult = createTask(recommendationId, patientId, referralId,
                    triage.priority(), title, description);
            UUID taskId = readTaskId(taskResult);
            trace.step("action", "created task " + taskId + " for the referral owner");

            emitCreated(recommendationId, triggerId, triggerType, referralId, patientId, taskId,
                    triage, trace.steps());
            log.info("Recommendation {} auto-applied (task {}) for referral {}",
                    recommendationId, taskId, referralId);
        } finally {
            inFlightReferrals.remove(referralId);
        }
    }

    /**
     * An open [Agent] task exists. If its run's Created event landed, this is the ordinary
     * one-open-task skip; if not (crash between create_task and emit), re-emit Created from the
     * task + ledger — no LLM re-run.
     */
    private void repairOrSkip(UUID referralId) {
        List<Map<String, Object>> ledger = guards.taskLedgerForReferral(referralId);
        for (Map<String, Object> row : ledger) {
            UUID recId = UUID.fromString(String.valueOf(row.get("recommendation_id")));
            if (guards.createdEventProcessed(recId) || guards.recommendationExists(recId)) {
                continue;
            }
            log.warn("Emit-repair: task exists for referral {} but recommendation {} never landed — re-emitting",
                    referralId, recId);
            String title = String.valueOf(row.get("title"));
            String description = String.valueOf(row.get("description"));
            UUID patientId = UUID.fromString(String.valueOf(row.get("patient_id")));
            UUID taskId = readTaskId(String.valueOf(row.get("result")));
            Triage reconstructed = new Triage(description, "see task description", "MEDIUM", title);
            TraceRecorder trace = new TraceRecorder();
            trace.step("trigger", "emit-repair after crash window (referral " + referralId + ")");
            trace.step("action", "task " + taskId + " already created; recommendation re-emitted");
            emitCreated(recId, null, "EmitRepair", referralId, patientId, taskId, reconstructed,
                    trace.steps());
            return;
        }
        log.info("Open [Agent] task already routed for referral {} — skipping", referralId);
    }

    private String createTask(UUID recommendationId, UUID patientId, UUID referralId, String priority,
            String title, String description) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("recommendationId", recommendationId.toString());
        args.put("patientId", patientId.toString());
        args.put("referralId", referralId.toString());
        args.put("priority", priority != null ? priority : "MEDIUM");
        args.put("title", title);
        args.put("description", description);
        McpSchema.CallToolResult result = healthrx.callTool(
                new McpSchema.CallToolRequest("create_task", args));
        String text = firstText(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("create_task failed: " + text);
        }
        return text;
    }

    private void emitCreated(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId,
            UUID patientId, UUID taskId, Triage triage, List<Map<String, Object>> trace) {
        Instant occurredAt = guards.simClock().now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendationId);
        payload.put("agentName", props.name());
        payload.put("patientId", patientId);
        payload.put("referralId", referralId);
        payload.put("taskId", taskId);
        if (triggerId != null) {
            payload.put("triggerEventId", triggerId);
        }
        payload.put("triggerEventType", triggerType);
        payload.put("status", "AUTO_APPLIED");
        payload.put("summary", triage.summary() != null && !triage.summary().isBlank()
                ? triage.summary() : "Access follow-up task routed to the referral owner");
        payload.put("recommendation", Map.of(
                "caseSummary", nullSafe(triage.summary()),
                "nextAction", nullSafe(triage.nextAction()),
                "task", Map.of(
                        "taskId", taskId != null ? taskId.toString() : "",
                        "title", nullSafe(triage.taskTitle()),
                        "priority", triage.priority() != null ? triage.priority() : "MEDIUM")));
        payload.put("trace", trace);
        publisher.publish(AgentIds.createdEventId(recommendationId), "AgentRecommendationCreated",
                occurredAt, payload);
    }

    private UUID readTaskId(String taskResultJson) {
        try {
            return UUID.fromString(mapper.readTree(taskResultJson).path("taskId").asText());
        } catch (Exception e) {
            log.warn("create_task result unreadable: {}", taskResultJson);
            return null;
        }
    }

    private static String firstText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        var first = result.content().get(0);
        return first instanceof McpSchema.TextContent tc ? tc.text() : String.valueOf(first);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static UUID uuid(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : UUID.fromString(v.toString());
    }

    McpSql sqlAccess() {
        return sql;
    }
}
