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
import com.healthrx.agent.access.mcp.McpSql;
import com.healthrx.agent.access.messaging.EventEnvelope;
import com.healthrx.agent.access.messaging.EventPublisher;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The benefits-investigation beat: when a referral enters BENEFITS_INVESTIGATION (one human
 * advance — from the queue or by completing the intake task), the agent runs the coverage
 * check against the case record and submits the prior authorization itself through the audited
 * MCP action tools. The resulting PRIOR_AUTH_SUBMITTED re-broadcast hands the case straight to
 * the payer follow-up beat, so a single human touch chains agent-side to Ready to fill.
 * Deterministic (no LLM): it must land within seconds on stage.
 */
@Service
public class BenefitsCheckService {

    private static final Logger log = LoggerFactory.getLogger(BenefitsCheckService.class);

    private final Guards guards;
    private final McpSql sql;
    private final McpSyncClient healthrx;
    private final EventPublisher publisher;
    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final RateLimiter benefitsRate;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public BenefitsCheckService(Guards guards, McpSql sql, McpSyncClient healthrxMcp,
            EventPublisher publisher, AgentProperties props, ObjectMapper mapper) {
        this.guards = guards;
        this.sql = sql;
        this.healthrx = healthrxMcp;
        this.publisher = publisher;
        this.props = props;
        this.mapper = mapper;
        this.benefitsRate = new RateLimiter(Math.max(1, props.benefitsRatePerMinute()));
    }

    public void onBenefitsInvestigationStarted(EventEnvelope env) {
        Map<String, Object> p = env.payload() == null ? Map.of() : env.payload();
        Object rid = p.get("referralId");
        if (rid == null) {
            log.warn("BenefitsInvestigationStarted without referralId — skipping. eventId={}", env.eventId());
            return;
        }
        UUID referralId = UUID.fromString(rid.toString());
        UUID recommendationId = AgentIds.recommendationId(props.name(), env.eventId());

        if (!inFlight.add(referralId)) {
            log.info("Benefits check already in flight for referral {} — skipping", referralId);
            return;
        }
        try {
            if (guards.paused()) {
                log.info("Agent paused — skipping benefits check for {}", referralId);
                return;
            }
            if (guards.recommendationExists(recommendationId)) {
                return;
            }
            // Human-driven advances (the API re-broadcasts those) are never rate-capped —
            // that's the presenter's on-stage moment; the cap only sheds ambient-stream churn,
            // which the ambient flow itself resolves later.
            boolean humanDriven = "healthrx-api".equals(env.source());
            if (!humanDriven && !benefitsRate.tryAcquire()) {
                log.warn("Benefits rate cap hit — ambient investigation for referral {} dropped "
                        + "(the ambient flow will resolve it later)", referralId);
                return;
            }
            // Make sure the API applied the status change before we try to submit against it.
            guards.waitForProcessed(env.eventId());
            run(recommendationId, env.eventId(), env.eventType(), referralId);
        } finally {
            inFlight.remove(referralId);
        }
    }

    private void run(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId) {
        TraceRecorder trace = new TraceRecorder();
        trace.step("trigger", "benefits investigation started for referral " + referralId
                + " — running the coverage check");

        String contextSql = """
                select r.referral_number, r.current_status, r.patient_id, r.copay_amount,
                       r.financial_assistance_required,
                       p.first_name || ' ' || p.last_name as patient_name,
                       m.name as medication, pay.name as payer, pay.payer_type,
                       ct.display_name as owner
                from referrals r
                join patients p on p.id = r.patient_id
                join medications m on m.id = r.medication_id
                join payers pay on pay.id = r.payer_id
                join care_team_members ct on ct.id = r.owner_id
                where r.id = '%s' limit 1""".formatted(referralId);
        List<Map<String, Object>> rows = sql.query(contextSql);
        trace.toolCall("executeQuery", "look up referral, patient, medication, payer and plan for " + referralId,
                rows.isEmpty() ? "no rows" : String.valueOf(rows.get(0)));
        if (rows.isEmpty()) {
            log.warn("Referral {} not found — skipping benefits check", referralId);
            return;
        }
        Map<String, Object> ctx = rows.get(0);
        String status = str(ctx, "current_status");
        UUID patientId = UUID.fromString(str(ctx, "patient_id"));
        String payer = str(ctx, "payer");
        if (!"BENEFITS_INVESTIGATION".equals(status)) {
            // Emit-repair (mirrors PayerCheckService): if a crash/broker blip hit between the
            // submission and the Created event, the ledger proves the work — restore the feed
            // entry instead of leaving an invisible action.
            if (repairEmit(recommendationId, triggerId, triggerType, referralId, patientId, payer)) {
                return;
            }
            log.info("Referral {} is {} (not in benefits investigation) — skipping", referralId, status);
            return;
        }
        String referralNumber = str(ctx, "referral_number");
        String patientName = str(ctx, "patient_name");
        String medication = str(ctx, "medication");
        String payerType = str(ctx, "payer_type");
        String copay = str(ctx, "copay_amount");
        boolean faRequired = Boolean.parseBoolean(str(ctx, "financial_assistance_required"));

        String coverage = "verified " + payer + " (" + payerType + ") coverage for " + medication
                + ": specialty tier, prior authorization required"
                + (copay != null ? "; expected copay $" + copay : "")
                + (faRequired ? "; financial assistance flagged for follow-up" : "");
        trace.step("action", coverage);

        String note = "Benefits investigation completed by the Access Workflow Agent — " + payer
                + " (" + payerType + ") covers " + medication + " with PA required; prior auth submitted.";
        Map<String, Object> submitted = callHealthRxTool("submit_prior_auth", Map.of(
                "recommendationId", recommendationId.toString(),
                "referralId", referralId.toString(),
                "note", note));
        trace.toolCall("healthrx.submit_prior_auth",
                "submit PA for " + referralNumber + " (" + medication + ", " + payer + ")",
                Boolean.TRUE.equals(submitted.get("applied"))
                        ? "applied — referral advanced to Prior auth submitted"
                        : "not applied — referral is " + submitted.get("currentStatus"));
        if (!Boolean.TRUE.equals(submitted.get("applied"))) {
            log.info("Referral {} had already moved on ({}) — no PA submitted",
                    referralId, submitted.get("currentStatus"));
            return;
        }
        trace.step("action", "submitted the prior authorization to " + payer
                + " — the payer follow-up beat chases the decision next");

        String summary = "Benefits check complete for " + patientName + " (" + referralNumber + ") — "
                + payer + " coverage verified and the prior auth submitted; awaiting the payer's decision";
        emitCreated(recommendationId, triggerId, triggerType, referralId, patientId,
                summary, payer, payerType, medication, copay, faRequired, trace);
        log.info("Benefits check complete for referral {}: PA submitted (recommendation {})",
                referralId, recommendationId);
    }

    /**
     * Re-emits the Created event for a submission this run already made (crash window between
     * the ledgered MCP write and the event publish). Returns false when the ledger holds nothing
     * for this recommendation — i.e. the referral simply moved on before we got to it.
     */
    private boolean repairEmit(UUID recommendationId, UUID triggerId, String triggerType,
            UUID referralId, UUID patientId, String payer) {
        List<Map<String, Object>> ledger = sql.query("""
                select result::text as result from agent_tool_calls
                where recommendation_id = '%s' and tool_name = 'submit_prior_auth'"""
                .formatted(recommendationId));
        for (Map<String, Object> row : ledger) {
            try {
                Map<String, Object> result = mapper.readValue(str(row, "result"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                if (!Boolean.TRUE.equals(result.get("applied"))) {
                    continue;
                }
            } catch (Exception e) {
                log.warn("Unreadable ledger row during emit-repair for {}", recommendationId, e);
                continue;
            }
            log.warn("Emit-repair: PA submission for referral {} was recorded but its feed entry "
                    + "never landed — re-emitting", referralId);
            TraceRecorder trace = new TraceRecorder();
            trace.step("trigger", "emit-repair after an interruption (referral " + referralId + ")");
            trace.step("action", "prior auth was already submitted; feed entry restored");
            String summary = "Benefits check complete — prior auth submitted earlier; feed entry "
                    + "restored after an interruption";
            emitCreated(recommendationId, triggerId, triggerType, referralId, patientId,
                    summary, payer, null, null, null, false, trace);
            return true;
        }
        return false;
    }

    private void emitCreated(UUID recommendationId, UUID triggerId, String triggerType, UUID referralId,
            UUID patientId, String summary, String payer, String payerType, String medication,
            String copay, boolean faRequired, TraceRecorder trace) {
        Instant occurredAt = guards.simClock().now();

        Map<String, Object> benefitsCheck = new LinkedHashMap<>();
        benefitsCheck.put("payer", payer);
        if (payerType != null) {
            benefitsCheck.put("payerType", payerType);
        }
        if (medication != null) {
            benefitsCheck.put("medication", medication);
        }
        benefitsCheck.put("priorAuthRequired", true);
        if (copay != null) {
            benefitsCheck.put("expectedCopay", copay);
        }
        if (faRequired) {
            benefitsCheck.put("financialAssistanceFlagged", true);
        }

        Map<String, Object> recommendation = new LinkedHashMap<>();
        recommendation.put("caseSummary", summary);
        recommendation.put("nextAction",
                "None — the payer follow-up beat chases the prior-auth decision automatically.");
        recommendation.put("benefitsCheck", benefitsCheck);

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

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }
}
