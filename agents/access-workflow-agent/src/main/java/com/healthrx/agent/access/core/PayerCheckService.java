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
import com.healthrx.agent.access.messaging.EventEnvelope;
import com.healthrx.agent.access.messaging.EventPublisher;
import com.healthrx.agent.access.mcp.McpSql;
import com.healthrx.agent.access.partner.PayerPortalClient;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The payer follow-up beat: when a referral's prior auth is submitted, the agent autonomously
 * contacts the external ClearPath payer portal for a decision and records it back through the
 * audited MCP action tools — APPROVED advances the referral, DENIED also routes a HIGH appeal
 * task to the owner. Deterministic (no LLM): the value on display is autonomous external
 * coordination, and it must land within seconds on stage.
 */
@Service
public class PayerCheckService {

    private static final Logger log = LoggerFactory.getLogger(PayerCheckService.class);
    private static final String PORTAL_NAME = "ClearPath Benefits";

    private final Guards guards;
    private final McpSql sql;
    private final PayerPortalClient portal;
    private final McpSyncClient healthrx;
    private final EventPublisher publisher;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final RateLimiter payerRate;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public PayerCheckService(Guards guards, McpSql sql, PayerPortalClient portal,
            McpSyncClient healthrxMcp, EventPublisher publisher, AgentProperties props, ObjectMapper mapper) {
        this.guards = guards;
        this.sql = sql;
        this.portal = portal;
        this.healthrx = healthrxMcp;
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.payerRate = new RateLimiter(Math.max(1, props.payerRatePerMinute()));
    }

    public void onPriorAuthSubmitted(EventEnvelope env) {
        Map<String, Object> p = env.payload() == null ? Map.of() : env.payload();
        Object rid = p.get("referralId");
        if (rid == null) {
            log.warn("PriorAuthorizationSubmitted without referralId — skipping. eventId={}", env.eventId());
            return;
        }
        UUID referralId = UUID.fromString(rid.toString());
        UUID recommendationId = AgentIds.recommendationId(props.name(), env.eventId());

        if (!inFlight.add(referralId)) {
            log.info("Payer check already in flight for referral {} — skipping", referralId);
            return;
        }
        try {
            if (guards.paused()) {
                log.info("Agent paused — skipping payer check for {}", referralId);
                return;
            }
            if (guards.recommendationExists(recommendationId)) {
                return;
            }
            // Human-driven submissions (the API re-broadcasts those) are never rate-capped —
            // that's the presenter's on-stage moment; the cap only sheds ambient-stream churn,
            // which the ambient flow itself resolves later.
            boolean humanDriven = "healthrx-api".equals(env.source());
            if (!humanDriven && !payerRate.tryAcquire()) {
                log.warn("Payer rate cap hit — ambient submission for referral {} dropped "
                        + "(the ambient flow will resolve it later)", referralId);
                return;
            }
            // Make sure the API applied the submission before we try to record a decision on it.
            guards.waitForProcessed(env.eventId());
            run(recommendationId, env.eventId(), env.eventType(), referralId);
        } finally {
            inFlight.remove(referralId);
        }
    }

    private void run(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId) {
        TraceRecorder trace = new TraceRecorder();
        trace.step("trigger", "prior auth submitted for referral " + referralId
                + " — following up with the payer for a decision");

        String contextSql = """
                select r.referral_number, r.current_status, r.patient_id,
                       p.first_name || ' ' || p.last_name as patient_name,
                       m.name as medication, pay.name as payer, ct.display_name as owner
                from referrals r
                join patients p on p.id = r.patient_id
                join medications m on m.id = r.medication_id
                join payers pay on pay.id = r.payer_id
                join care_team_members ct on ct.id = r.owner_id
                where r.id = '%s' limit 1""".formatted(referralId);
        List<Map<String, Object>> rows = sql.query(contextSql);
        trace.toolCall("executeQuery", "look up referral, patient, medication and payer for " + referralId,
                rows.isEmpty() ? "no rows" : String.valueOf(rows.get(0)));
        if (rows.isEmpty()) {
            log.warn("Referral {} not found — skipping payer check", referralId);
            return;
        }
        Map<String, Object> ctx = rows.get(0);
        String status = str(ctx, "current_status");
        UUID ctxPatientId = UUID.fromString(str(ctx, "patient_id"));
        if (!"PRIOR_AUTH_SUBMITTED".equals(status)) {
            // Emit-repair (mirrors AccessAgentService.repairOrSkip): if a crash/broker blip hit
            // between recording the decision and emitting the Created event, the ledger proves
            // the decision — restore the feed entry instead of leaving an invisible action.
            if (repairEmit(recommendationId, triggerId, triggerType, referralId, ctxPatientId,
                    str(ctx, "payer"))) {
                return;
            }
            log.info("Referral {} is {} (not awaiting a PA decision) — skipping", referralId, status);
            return;
        }
        String referralNumber = str(ctx, "referral_number");
        String patientName = str(ctx, "patient_name");
        String medication = str(ctx, "medication");
        String payer = str(ctx, "payer");
        UUID patientId = UUID.fromString(str(ctx, "patient_id"));

        trace.step("action", "contacting " + PORTAL_NAME + " (external payer portal) about "
                + medication + " for " + patientName + " — plan: " + payer);
        long started = System.currentTimeMillis();
        Map<String, Object> decision;
        try {
            decision = portal.requestDecision(referralNumber, medication, payer);
        } catch (Exception e) {
            log.warn("Payer portal unreachable for referral {} — the stuck scan will retry later", referralId, e);
            return;
        }
        long elapsed = System.currentTimeMillis() - started;
        String verdict = str(decision, "decision");
        String authNumber = str(decision, "authorizationNumber");
        String denialReason = str(decision, "denialReason");
        String reviewer = str(decision, "reviewer");
        trace.toolCall("clearpath_portal.prior_auth_decision",
                "POST /api/prior-auth/decision {referral " + referralNumber + ", " + medication + "}",
                verdict + (authNumber != null ? " — auth " + authNumber : "")
                        + (denialReason != null ? " — " + denialReason : "")
                        + " (reviewed by " + reviewer + " in " + (elapsed / 1000.0) + "s)");

        boolean approved = "APPROVED".equals(verdict);
        if (!approved && !"DENIED".equals(verdict)) {
            log.warn("Unexpected payer decision '{}' for referral {} — skipping", verdict, referralId);
            return;
        }

        String note = approved
                ? "Prior auth APPROVED by " + payer + " via " + PORTAL_NAME + " (auth " + authNumber
                        + ", " + (elapsed / 1000.0) + "s) — obtained autonomously by the Access Workflow Agent"
                : "Prior auth DENIED by " + payer + " via " + PORTAL_NAME + ": " + denialReason
                        + " — recorded autonomously by the Access Workflow Agent";
        Map<String, Object> recorded = callHealthRxTool("record_prior_auth_decision", Map.of(
                "recommendationId", recommendationId.toString(),
                "referralId", referralId.toString(),
                "decision", verdict,
                "authorizationNumber", authNumber == null ? "" : authNumber,
                "note", note));
        if (!Boolean.TRUE.equals(recorded.get("applied"))) {
            log.info("Referral {} had already moved on ({}) — no decision recorded",
                    referralId, recorded.get("currentStatus"));
            return;
        }

        UUID taskId = null;
        String summary;
        if (approved) {
            trace.step("action", "recorded the approval — referral advanced to Prior auth approved (auth "
                    + authNumber + ")");
            summary = "Obtained prior-auth approval from " + payer + " for " + patientName + " ("
                    + referralNumber + ") in " + (elapsed / 1000.0) + "s — referral advanced to Prior auth approved";
        } else {
            trace.step("action", "recorded the denial — referral moved to Prior auth denied");
            Map<String, Object> task = callHealthRxTool("create_task", Map.of(
                    "recommendationId", recommendationId.toString(),
                    "patientId", patientId.toString(),
                    "referralId", referralId.toString(),
                    "priority", "HIGH",
                    "title", "Appeal PA denial for " + referralNumber,
                    "description", "The payer (" + payer + ") denied the prior auth via " + PORTAL_NAME
                            + ".\nReason: " + denialReason
                            + "\n\nRecommended next action: gather the missing documentation and resubmit —"
                            + " the portal fast-tracks resubmissions."));
            Object tid = task.get("taskId");
            taskId = tid == null ? null : UUID.fromString(tid.toString());
            trace.step("action", "routed a HIGH-priority appeal task to " + str(ctx, "owner"));
            summary = "Prior auth denied by " + payer + " for " + patientName + " (" + referralNumber
                    + ") — denial recorded and a HIGH-priority appeal task routed to " + str(ctx, "owner");
        }

        emitCreated(recommendationId, triggerId, triggerType, referralId, patientId, taskId,
                summary, verdict, payer, authNumber, denialReason, reviewer, elapsed, trace);
        log.info("Payer check complete for referral {}: {} (recommendation {})",
                referralId, verdict, recommendationId);
    }

    /**
     * Re-emits the Created event for a decision this run already recorded (crash window between
     * the ledgered MCP write and the event publish). Returns false when the ledger holds nothing
     * for this recommendation — i.e. the referral simply moved on before we got to it.
     */
    private boolean repairEmit(UUID recommendationId, UUID triggerId, String triggerType,
            UUID referralId, UUID patientId, String payer) {
        List<Map<String, Object>> ledger = sql.query("""
                select tool_name, result::text as result from agent_tool_calls
                where recommendation_id = '%s'""".formatted(recommendationId));
        Map<String, Object> decision = null;
        UUID taskId = null;
        for (Map<String, Object> row : ledger) {
            try {
                Map<String, Object> result = mapper.readValue(str(row, "result"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                if ("record_prior_auth_decision".equals(str(row, "tool_name"))) {
                    decision = result;
                } else if ("create_task".equals(str(row, "tool_name")) && result.get("taskId") != null) {
                    taskId = UUID.fromString(result.get("taskId").toString());
                }
            } catch (Exception e) {
                log.warn("Unreadable ledger row during emit-repair for {}", recommendationId, e);
            }
        }
        if (decision == null || !Boolean.TRUE.equals(decision.get("applied"))) {
            return false;
        }
        String verdict = "PRIOR_AUTH_DENIED".equals(str(decision, "newStatus")) ? "DENIED" : "APPROVED";
        String authNumber = str(decision, "authorizationNumber");
        log.warn("Emit-repair: decision {} for referral {} was recorded but its feed entry never "
                + "landed — re-emitting", verdict, referralId);
        TraceRecorder trace = new TraceRecorder();
        trace.step("trigger", "emit-repair after an interruption (referral " + referralId + ")");
        trace.step("action", "payer decision " + verdict + " was already recorded"
                + (authNumber != null ? " (auth " + authNumber + ")" : "") + "; feed entry restored");
        String summary = "Prior auth " + verdict.toLowerCase() + " by " + payer
                + " — decision recorded earlier; feed entry restored after an interruption";
        emitCreated(recommendationId, triggerId, triggerType, referralId, patientId, taskId,
                summary, verdict, payer, authNumber, null, null, 0, trace);
        return true;
    }

    private void emitCreated(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId,
            UUID patientId, UUID taskId, String summary, String verdict, String payer, String authNumber,
            String denialReason, String reviewer, long elapsedMs, TraceRecorder trace) {
        Instant occurredAt = guards.simClock().now();
        Map<String, Object> payerDecision = new LinkedHashMap<>();
        payerDecision.put("portal", PORTAL_NAME);
        payerDecision.put("payer", payer);
        payerDecision.put("decision", verdict);
        if (authNumber != null && !authNumber.isBlank()) {
            payerDecision.put("authorizationNumber", authNumber);
        }
        if (denialReason != null && !denialReason.isBlank()) {
            payerDecision.put("denialReason", denialReason);
        }
        if (reviewer != null && !reviewer.isBlank()) {
            payerDecision.put("reviewer", reviewer);
        }
        if (elapsedMs > 0) {
            payerDecision.put("turnaroundSeconds", elapsedMs / 1000.0);
        }

        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("caseSummary", summary);
        recommendation.put("nextAction", "APPROVED".equals(verdict)
                ? "None — the agent already advanced the referral; continue toward Ready to fill."
                : "Appeal the denial: attach the documentation the payer flagged and resubmit the PA.");
        recommendation.put("payerDecision", payerDecision);
        if (taskId != null) {
            recommendation.put("task", Map.of(
                    "taskId", taskId.toString(),
                    "title", "Appeal PA denial",
                    "priority", "HIGH"));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendationId);
        payload.put("agentName", props.name());
        payload.put("patientId", patientId);
        payload.put("referralId", referralId);
        if (taskId != null) {
            payload.put("taskId", taskId);
        }
        payload.put("triggerEventId", triggerId);
        payload.put("triggerEventType", triggerType);
        payload.put("status", "AUTO_APPLIED");
        payload.put("summary", summary);
        payload.put("recommendation", recommendation);
        payload.put("trace", trace.steps());
        publisher.publish(AgentIds.createdEventId(recommendationId), "AgentRecommendationCreated",
                occurredAt, payload);
    }

    private Map<String, Object> callHealthRxTool(String tool, Map<String, Object> args) {
        McpSchema.CallToolResult result = healthrx.callTool(new McpSchema.CallToolRequest(tool, args));
        String text = firstText(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException(tool + " failed: " + text);
        }
        try {
            return mapper.readValue(text, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(tool + " returned non-JSON: " + text, e);
        }
    }

    private static String firstText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        var first = result.content().get(0);
        return first instanceof McpSchema.TextContent tc ? tc.text() : String.valueOf(first);
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}
