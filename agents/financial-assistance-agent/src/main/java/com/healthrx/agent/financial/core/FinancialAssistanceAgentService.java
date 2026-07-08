package com.healthrx.agent.financial.core;

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
import com.healthrx.agent.financial.config.AgentProperties;
import com.healthrx.agent.financial.llm.AssistanceEngine;
import com.healthrx.agent.financial.mcp.McpSql;
import com.healthrx.agent.financial.messaging.EventEnvelope;
import com.healthrx.agent.financial.messaging.EventPublisher;
import com.healthrx.agent.financial.partner.BridgeFundPortalClient;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The run loop: when a referral's prior auth is approved, check whether it actually needs
 * financial assistance. If not, advance straight to READY_TO_FILL — no external call, no
 * decision made here, just reading a fact the case already carries. If it does, contact the
 * external BridgeFund Patient Assistance foundation for a real decision and record whatever it
 * says. Deterministic control flow; the one model call in the run only narrates the case for the
 * foundation and the audit trail — it never decides approve/deny/dollar-amount.
 */
@Service
public class FinancialAssistanceAgentService {

    private static final Logger log = LoggerFactory.getLogger(FinancialAssistanceAgentService.class);
    private static final String PROGRAM_NAME = "BridgeFund Patient Assistance";

    private final Guards guards;
    private final McpSql sql;
    private final BridgeFundPortalClient portal;
    private final AssistanceEngine assistanceEngine;
    private final McpSyncClient healthrx;
    private final EventPublisher publisher;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final RateLimiter rate;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public FinancialAssistanceAgentService(Guards guards, McpSql sql, BridgeFundPortalClient portal,
            AssistanceEngine assistanceEngine, McpSyncClient healthrxMcp, EventPublisher publisher,
            AgentProperties props, ObjectMapper mapper, RateLimiter rate) {
        this.guards = guards;
        this.sql = sql;
        this.portal = portal;
        this.assistanceEngine = assistanceEngine;
        this.healthrx = healthrxMcp;
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.rate = rate;
    }

    public void onPriorAuthorizationApproved(EventEnvelope env) {
        Map<String, Object> p = env.payload() == null ? Map.of() : env.payload();
        Object rid = p.get("referralId");
        if (rid == null) {
            log.warn("PriorAuthorizationApproved without referralId — skipping. eventId={}", env.eventId());
            return;
        }
        UUID referralId = UUID.fromString(rid.toString());
        UUID recommendationId = AgentIds.recommendationId(props.name(), env.eventId());

        if (!inFlight.add(referralId)) {
            log.info("Financial-assistance check already in flight for referral {} — skipping", referralId);
            return;
        }
        try {
            if (guards.paused()) {
                log.info("Agent paused — skipping financial-assistance check for {}", referralId);
                return;
            }
            if (guards.recommendationExists(recommendationId)) {
                return;
            }
            if (!rate.tryAcquire()) {
                log.warn("Rate cap hit — skipping financial-assistance check for referral {}", referralId);
                return;
            }
            guards.waitForProcessed(env.eventId());
            if (!guards.referralExists(referralId)) {
                log.info("Referral {} does not exist — no financial-assistance check", referralId);
                return;
            }
            run(recommendationId, env.eventId(), env.eventType(), referralId);
        } finally {
            inFlight.remove(referralId);
        }
    }

    private void run(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId) {
        TraceRecorder trace = new TraceRecorder();
        trace.step("trigger", "prior auth approved for referral " + referralId
                + " — checking whether the case needs financial assistance");

        String contextSql = """
                select r.referral_number, r.current_status, r.patient_id,
                       r.financial_assistance_required, r.copay_amount,
                       p.first_name || ' ' || p.last_name as patient_name, p.first_name as patient_first_name,
                       p.disease_state, m.name as medication, ct.display_name as owner
                from referrals r
                join patients p on p.id = r.patient_id
                join medications m on m.id = r.medication_id
                join care_team_members ct on ct.id = r.owner_id
                where r.id = '%s' limit 1""".formatted(referralId);
        List<Map<String, Object>> rows = sql.query(contextSql);
        trace.toolCall("executeQuery", "look up referral, patient, and financial-assistance flag for " + referralId,
                rows.isEmpty() ? "no rows" : String.valueOf(rows.get(0)));
        if (rows.isEmpty()) {
            log.warn("Referral {} not found — skipping financial-assistance check", referralId);
            return;
        }
        Map<String, Object> ctx = rows.get(0);
        String status = str(ctx, "current_status");
        if (!"PRIOR_AUTH_APPROVED".equals(status)) {
            log.info("Referral {} is {} (not awaiting a financial-assistance check) — skipping", referralId, status);
            return;
        }
        String referralNumber = str(ctx, "referral_number");
        String patientName = str(ctx, "patient_name");
        String patientFirstName = str(ctx, "patient_first_name");
        String medication = str(ctx, "medication");
        UUID patientId = UUID.fromString(str(ctx, "patient_id"));
        boolean required = Boolean.parseBoolean(str(ctx, "financial_assistance_required"));

        if (!required) {
            trace.step("action", "financial assistance isn't required for this case — advancing "
                    + "straight to ready-to-fill, no foundation contact needed");
            Map<String, Object> recorded = callHealthRxTool("record_financial_assistance_decision", Map.of(
                    "recommendationId", recommendationId.toString(),
                    "referralId", referralId.toString(),
                    "decision", "NOT_REQUIRED",
                    "note", "Financial assistance not required for this case."));
            if (!Boolean.TRUE.equals(recorded.get("applied"))) {
                log.info("Referral {} had already moved on — no action taken", referralId);
                return;
            }
            String summary = patientName + "'s referral (" + referralNumber
                    + ") doesn't need financial assistance — advanced straight to ready-to-fill.";
            emitCreated(recommendationId, triggerId, triggerType, referralId, patientId, summary,
                    "NOT_REQUIRED", null, null, null, 0, trace);
            log.info("Financial-assistance check complete for referral {}: not required", referralId);
            return;
        }

        trace.step("action", "contacting " + PROGRAM_NAME + " (external patient-assistance foundation) "
                + "about " + medication + " for " + patientName);

        String justification = assistanceEngine.summarize(patientFirstName, medication, str(ctx, "disease_state"), trace);
        Integer copay = toInt(ctx.get("copay_amount"));

        long started = System.currentTimeMillis();
        Map<String, Object> decisionResponse;
        try {
            decisionResponse = portal.requestDecision(referralNumber, medication, copay, justification);
        } catch (Exception e) {
            log.warn("BridgeFund portal unreachable for referral {} — leaving the case for a later retry",
                    referralId, e);
            return;
        }
        long elapsed = System.currentTimeMillis() - started;
        String verdict = str(decisionResponse, "decision");
        Object securedRaw = decisionResponse.get("securedAmount");
        String denialReason = str(decisionResponse, "denialReason");
        String reviewer = str(decisionResponse, "reviewer");
        trace.toolCall("bridgefund_portal.financial_assistance_decision",
                "POST /api/financial-assistance/decision {referral " + referralNumber + ", " + medication + "}",
                verdict + (securedRaw != null ? " — $" + securedRaw + " secured" : "")
                        + (denialReason != null ? " — " + denialReason : "")
                        + " (reviewed by " + reviewer + " in " + (elapsed / 1000.0) + "s)");

        boolean approved = "APPROVED".equals(verdict);
        if (!approved && !"DENIED".equals(verdict)) {
            log.warn("Unexpected assistance decision '{}' for referral {} — skipping", verdict, referralId);
            return;
        }

        String note = approved
                ? "Financial assistance APPROVED by " + PROGRAM_NAME + " ($" + securedRaw + ", "
                        + (elapsed / 1000.0) + "s) — obtained autonomously by the Financial Assistance Agent"
                : "Financial assistance DENIED by " + PROGRAM_NAME + ": " + denialReason
                        + " — recorded autonomously by the Financial Assistance Agent; proceeding to fill";
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("recommendationId", recommendationId.toString());
        args.put("referralId", referralId.toString());
        args.put("decision", verdict);
        if (securedRaw != null) {
            args.put("securedAmount", ((Number) securedRaw).doubleValue());
        }
        args.put("note", note);
        Map<String, Object> recorded = callHealthRxTool("record_financial_assistance_decision", args);
        if (!Boolean.TRUE.equals(recorded.get("applied"))) {
            log.info("Referral {} had already moved on ({}) — no decision recorded",
                    referralId, recorded.get("currentStatus"));
            return;
        }

        trace.step("action", approved
                ? "recorded the approval — referral advanced to ready-to-fill with the secured amount"
                : "recorded the denial — referral still advanced to ready-to-fill (assistance is supplementary)");
        String summary = approved
                ? "Secured $" + securedRaw + " in copay assistance from " + PROGRAM_NAME + " for " + patientName
                        + " (" + referralNumber + ") in " + (elapsed / 1000.0) + "s — referral advanced to ready-to-fill"
                : "No financial assistance secured for " + patientName + " (" + referralNumber + ") — "
                        + PROGRAM_NAME + " declined; referral still advanced to ready-to-fill";

        emitCreated(recommendationId, triggerId, triggerType, referralId, patientId, summary,
                verdict, securedRaw != null ? ((Number) securedRaw).intValue() : null, denialReason, reviewer,
                elapsed, trace);
        log.info("Financial-assistance check complete for referral {}: {}", referralId, verdict);
    }

    private void emitCreated(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId,
            UUID patientId, String summary, String verdict, Integer securedAmount, String denialReason,
            String reviewer, long elapsedMs, TraceRecorder trace) {
        Instant occurredAt = guards.simClock().now();
        Map<String, Object> decisionPayload = new LinkedHashMap<>();
        decisionPayload.put("program", PROGRAM_NAME);
        decisionPayload.put("decision", verdict);
        if (securedAmount != null) {
            decisionPayload.put("securedAmount", securedAmount);
        }
        if (denialReason != null && !denialReason.isBlank()) {
            decisionPayload.put("denialReason", denialReason);
        }
        if (reviewer != null && !reviewer.isBlank()) {
            decisionPayload.put("reviewer", reviewer);
        }
        if (elapsedMs > 0) {
            decisionPayload.put("turnaroundSeconds", elapsedMs / 1000.0);
        }

        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("caseSummary", summary);
        recommendation.put("nextAction", "None — the agent already advanced the referral to ready-to-fill.");
        recommendation.put("financialAssistanceDecision", decisionPayload);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendationId);
        payload.put("agentName", props.name());
        payload.put("patientId", patientId);
        payload.put("referralId", referralId);
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

    private static Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}
