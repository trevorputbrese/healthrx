package com.healthrx.mcp;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.healthrx.domain.AgentName;
import com.healthrx.web.ApiException;

/**
 * The HealthRx-embedded MCP action tools (phase-3-design.md §5.2). Every tool authorizes the
 * calling agent at invocation time against a per-agent allow-list (design §2 guardrail 1) and
 * requires the caller's recommendationId for exactly-once execution via the
 * {@code agent_tool_calls} ledger.
 */
@Component
public class HealthRxActionTools {

    private static final Map<AgentName, Set<String>> ALLOWED = Map.of(
            AgentName.ADHERENCE_RISK, Set.of("log_outreach", "create_intervention", "record_prescription_fill"),
            AgentName.ACCESS_WORKFLOW, Set.of("create_task", "submit_prior_auth", "record_prior_auth_decision"),
            AgentName.FINANCIAL_ASSISTANCE, Set.of("record_financial_assistance_decision"));

    private final AgentActionService actions;

    public HealthRxActionTools(AgentActionService actions) {
        this.actions = actions;
    }

    @Tool(name = "log_outreach", description = """
            Log a patient outreach contact (e.g. a REACHED phone call) on behalf of the calling
            agent's approved recommendation. Returns the created outreach record as JSON.""")
    public String logOutreach(
            @ToolParam(description = "The approved recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Patient UUID") String patientId,
            @ToolParam(description = "Referral UUID (optional)", required = false) String referralId,
            @ToolParam(description = "Channel: PHONE, SMS, EMAIL or PORTAL") String channel,
            @ToolParam(description = "Outcome: REACHED, LEFT_MESSAGE, NO_ANSWER, DECLINED or NEEDS_FOLLOW_UP") String outcome,
            @ToolParam(description = "Free-text notes / the outreach script used", required = false) String notes) {
        AgentName agent = authorize("log_outreach");
        return actions.logOutreach(agent, parse(recommendationId, "recommendationId"),
                parse(patientId, "patientId"), parseOrNull(referralId), channel, outcome, notes);
    }

    @Tool(name = "create_intervention", description = """
            Create a clinical intervention (e.g. ADHERENCE_COUNSELING) on behalf of the calling
            agent's approved recommendation. Returns the created intervention as JSON.""")
    public String createIntervention(
            @ToolParam(description = "The approved recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Patient UUID") String patientId,
            @ToolParam(description = "Referral UUID (optional)", required = false) String referralId,
            @ToolParam(description = "Type: ADHERENCE_COUNSELING, SIDE_EFFECT_MANAGEMENT, DOSE_CLARIFICATION, "
                    + "LAB_MONITORING or CARE_COORDINATION") String interventionType,
            @ToolParam(description = "Intervention summary") String summary) {
        AgentName agent = authorize("create_intervention");
        return actions.createIntervention(agent, parse(recommendationId, "recommendationId"),
                parse(patientId, "patientId"), parseOrNull(referralId), interventionType, summary);
    }

    @Tool(name = "record_prescription_fill", description = """
            Record a dispensed prescription fill for a therapy (rolls the refill due date, which
            clears refill-overdue risk). Returns the created fill as JSON.""")
    public String recordPrescriptionFill(
            @ToolParam(description = "The approved recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Therapy UUID") String therapyId,
            @ToolParam(description = "Days supply (default 30)", required = false) Integer daysSupply,
            @ToolParam(description = "Dispense date YYYY-MM-DD (default: the simulated today)",
                    required = false) String dispensedAt) {
        authorize("record_prescription_fill");
        return actions.recordPrescriptionFill(parse(recommendationId, "recommendationId"),
                parse(therapyId, "therapyId"), daysSupply, dispensedAt);
    }

    @Tool(name = "create_task", description = """
            Create an ACCESS_FOLLOW_UP task assigned to the referral's owner, carrying the agent's
            case summary and recommended next action. Returns the created task (incl. taskId) as JSON.""")
    public String createTask(
            @ToolParam(description = "The recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Patient UUID") String patientId,
            @ToolParam(description = "Referral UUID") String referralId,
            @ToolParam(description = "Priority: LOW, MEDIUM, HIGH or URGENT (default MEDIUM)",
                    required = false) String priority,
            @ToolParam(description = "Short task title (an [Agent] prefix is added)") String title,
            @ToolParam(description = "Case summary + recommended next action") String description,
            @ToolParam(description = "Due instant ISO-8601 (default: +3 days simulated)",
                    required = false) String dueAt) {
        authorize("create_task");
        return actions.createTask(parse(recommendationId, "recommendationId"), parse(patientId, "patientId"),
                parse(referralId, "referralId"), priority, title, description, dueAt);
    }

    @Tool(name = "submit_prior_auth", description = """
            Submit the prior authorization for a referral whose benefits investigation is
            complete: advances BENEFITS_INVESTIGATION to PRIOR_AUTH_SUBMITTED, which hands the
            case to the payer follow-up beat. If the referral already moved on, returns
            applied=false with the current status. Returns JSON.""")
    public String submitPriorAuth(
            @ToolParam(description = "The recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Referral UUID") String referralId,
            @ToolParam(description = "Status-history note, e.g. coverage findings", required = false) String note) {
        AgentName agent = authorize("submit_prior_auth");
        return actions.submitPriorAuth(agent, parse(recommendationId, "recommendationId"),
                parse(referralId, "referralId"), note);
    }

    @Tool(name = "record_prior_auth_decision", description = """
            Record a prior-authorization decision obtained from the payer for a referral in
            PRIOR_AUTH_SUBMITTED: APPROVED advances it to PRIOR_AUTH_APPROVED, DENIED to
            PRIOR_AUTH_DENIED, with the payer context captured in the status history. If the
            referral already moved on, returns applied=false with the current status. Returns JSON.""")
    public String recordPriorAuthDecision(
            @ToolParam(description = "The recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Referral UUID") String referralId,
            @ToolParam(description = "Decision: APPROVED or DENIED") String decision,
            @ToolParam(description = "Payer authorization number (for approvals)", required = false) String authorizationNumber,
            @ToolParam(description = "Status-history note, e.g. payer name and turnaround", required = false) String note) {
        AgentName agent = authorize("record_prior_auth_decision");
        return actions.recordPriorAuthDecision(agent, parse(recommendationId, "recommendationId"),
                parse(referralId, "referralId"), decision, authorizationNumber, note);
    }

    @Tool(name = "record_financial_assistance_decision", description = """
            Record a financial-assistance decision for a referral in PRIOR_AUTH_APPROVED.
            NOT_REQUIRED skips assistance and advances straight to READY_TO_FILL. APPROVED/DENIED
            reflect an actual foundation decision: both still advance to READY_TO_FILL (assistance
            is supplementary, never a fulfillment blocker), passing through
            FINANCIAL_ASSISTANCE_REVIEW; an approval also records the secured amount. If the
            referral already moved on, returns applied=false with the current status. Returns JSON.""")
    public String recordFinancialAssistanceDecision(
            @ToolParam(description = "The recommendation id this action belongs to") String recommendationId,
            @ToolParam(description = "Referral UUID") String referralId,
            @ToolParam(description = "Decision: NOT_REQUIRED, APPROVED, or DENIED") String decision,
            @ToolParam(description = "Dollar amount secured (for approvals)", required = false) Double securedAmount,
            @ToolParam(description = "Status-history note, e.g. program name and turnaround", required = false) String note) {
        AgentName agent = authorize("record_financial_assistance_decision");
        return actions.recordFinancialAssistanceDecision(agent, parse(recommendationId, "recommendationId"),
                parse(referralId, "referralId"), decision,
                securedAmount != null ? java.math.BigDecimal.valueOf(securedAmount) : null, note);
    }

    /** Call-time authorization: a valid agent identity whose allow-list contains the tool. */
    private static AgentName authorize(String toolName) {
        AgentName agent = AgentCallContext.current()
                .orElseThrow(() -> ApiException.badRequest("AGENT_IDENTITY_REQUIRED",
                        "Action tools require a valid X-Agent-Id / X-Agent-Key identity.",
                        Map.of("tool", toolName)));
        if (!ALLOWED.getOrDefault(agent, Set.of()).contains(toolName)) {
            throw ApiException.badRequest("TOOL_NOT_ALLOWED",
                    "Agent " + agent.wireName() + " is not entitled to " + toolName + ".",
                    Map.of("tool", toolName, "agent", agent.wireName()));
        }
        return agent;
    }

    private static UUID parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("MISSING_FIELD", field + " is required.", Map.of("field", field));
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_UUID", field + " is not a UUID.", Map.of("field", field));
        }
    }

    private static UUID parseOrNull(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
    }
}
