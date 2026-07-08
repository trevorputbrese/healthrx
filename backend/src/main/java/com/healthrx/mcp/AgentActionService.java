package com.healthrx.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrx.config.AppTime;
import com.healthrx.domain.AgentName;
import com.healthrx.domain.InterventionType;
import com.healthrx.domain.OutreachChannel;
import com.healthrx.domain.OutreachOutcome;
import com.healthrx.domain.ReferralStatus;
import com.healthrx.repo.AgentToolCallRepository;
import com.healthrx.repo.ReferralRepository;
import com.healthrx.repo.TaskRepository;
import com.healthrx.repo.TherapyRepository;
import com.healthrx.service.FillService;
import com.healthrx.service.PatientService;
import com.healthrx.service.ReferralService;
import com.healthrx.web.ApiException;
import com.healthrx.web.EnumParsing;

/**
 * Executes the embedded MCP action tools with exactly-once semantics: the domain write and the
 * {@code agent_tool_calls} ledger row commit in one transaction (an explicit
 * {@link TransactionTemplate} — no proxy self-invocation), and a replayed call returns the stored
 * prior result instead of re-executing (phase-3-design.md §5.2). Deterministic entity ids are
 * derived from the recommendationId so retries cannot mint duplicates.
 */
@Service
public class AgentActionService {

    private static final Logger log = LoggerFactory.getLogger(AgentActionService.class);

    private final AgentToolCallRepository ledger;
    private final PatientService patients;
    private final FillService fills;
    private final TaskRepository tasks;
    private final TherapyRepository therapies;
    private final ReferralRepository referrals;
    private final ReferralService referralService;
    private final AppTime time;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public AgentActionService(AgentToolCallRepository ledger, PatientService patients, FillService fills,
            TaskRepository tasks, TherapyRepository therapies, ReferralRepository referrals,
            ReferralService referralService, AppTime time, ObjectMapper mapper,
            PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.patients = patients;
        this.fills = fills;
        this.tasks = tasks;
        this.therapies = therapies;
        this.referrals = referrals;
        this.referralService = referralService;
        this.time = time;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    public String logOutreach(AgentName agent, UUID recommendationId, UUID patientId, UUID referralId,
            String channel, String outcome, String notes) {
        return once(recommendationId, "log_outreach", () -> {
            var result = patients.logOutreach(patientId, referralId, agent.actorId(),
                    EnumParsing.require(OutreachChannel.class, channel, "channel"),
                    EnumParsing.require(OutreachOutcome.class, outcome, "outcome"),
                    null, notes);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("outreachId", result.id());
            out.put("outcome", result.outcome());
            out.put("occurredAt", result.occurredAt().toString());
            return out;
        });
    }

    public String createIntervention(AgentName agent, UUID recommendationId, UUID patientId, UUID referralId,
            String interventionType, String summary) {
        return once(recommendationId, "create_intervention", () -> {
            var result = patients.logIntervention(patientId, referralId, agent.actorId(),
                    EnumParsing.require(InterventionType.class, interventionType, "interventionType"),
                    summary, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("interventionId", result.id());
            out.put("interventionType", result.interventionType());
            out.put("occurredAt", result.occurredAt().toString());
            return out;
        });
    }

    public String recordPrescriptionFill(UUID recommendationId, UUID therapyId, Integer daysSupply,
            String dispensedAt) {
        return once(recommendationId, "record_prescription_fill", () -> {
            TherapyRepository.TherapyContext ctx = therapies.context(therapyId)
                    .orElseThrow(() -> ApiException.notFound("Therapy", therapyId));
            int supply = daysSupply != null ? daysSupply : 30;
            LocalDate dispensed = dispensedAt != null ? LocalDate.parse(dispensedAt) : time.today();
            UUID fillId = deterministicId(recommendationId, "fill");
            fills.record(fillId, ctx.patientId(), therapyId, ctx.referralId(), supply, dispensed);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fillId", fillId);
            out.put("dispensedAt", dispensed.toString());
            out.put("expectedRefillDate", dispensed.plusDays(supply).toString());
            return out;
        });
    }

    public String createTask(UUID recommendationId, UUID patientId, UUID referralId, String priority,
            String title, String description, String dueAt) {
        return once(recommendationId, "create_task", () -> {
            UUID ownerId = referrals.ownerOf(referralId)
                    .orElseThrow(() -> ApiException.notFound("Referral", referralId));
            Instant now = time.now();
            Instant due = dueAt != null ? Instant.parse(dueAt) : now.plusSeconds(3 * 86400L);
            String prefixed = title != null && title.startsWith("[Agent]") ? title : "[Agent] " + title;
            UUID taskId = deterministicId(recommendationId, "task");
            tasks.insert(taskId, patientId, referralId, ownerId, "ACCESS_FOLLOW_UP", "OPEN",
                    priority != null ? priority.toUpperCase() : "MEDIUM", prefixed, description, due, now);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("taskId", taskId);
            out.put("ownerId", ownerId);
            out.put("dueAt", due.toString());
            return out;
        });
    }

    /**
     * Records a payer's prior-authorization decision obtained by the calling agent. If the
     * referral has already moved past PRIOR_AUTH_SUBMITTED (a rare race with the ambient
     * simulation or a human), this is a benign no-op that reports the current status instead
     * of failing the agent's run.
     */
    public String recordPriorAuthDecision(AgentName agent, UUID recommendationId, UUID referralId,
            String decision, String authorizationNumber, String note) {
        ReferralStatus target = switch (decision == null ? "" : decision.toUpperCase()) {
            case "APPROVED" -> ReferralStatus.PRIOR_AUTH_APPROVED;
            case "DENIED" -> ReferralStatus.PRIOR_AUTH_DENIED;
            default -> throw ApiException.badRequest("INVALID_DECISION",
                    "decision must be APPROVED or DENIED.", Map.of("decision", String.valueOf(decision)));
        };
        return once(recommendationId, "record_prior_auth_decision", () -> {
            ReferralRepository.State state = referrals.loadState(referralId)
                    .orElseThrow(() -> ApiException.notFound("Referral", referralId));
            Map<String, Object> out = new LinkedHashMap<>();
            if (!ReferralStatus.PRIOR_AUTH_SUBMITTED.name().equals(state.currentStatus())) {
                out.put("applied", false);
                out.put("currentStatus", state.currentStatus());
                out.put("reason", "Referral is no longer awaiting a prior-auth decision.");
                return out;
            }
            referralService.transition(referralId, target.name(), agent.actorId(), note);
            out.put("applied", true);
            out.put("newStatus", target.name());
            if (authorizationNumber != null && !authorizationNumber.isBlank()) {
                out.put("authorizationNumber", authorizationNumber);
            }
            return out;
        });
    }

    /**
     * Records a financial-assistance decision obtained by the calling agent for a referral in
     * PRIOR_AUTH_APPROVED. {@code NOT_REQUIRED} skips assistance entirely (the case doesn't need
     * it) and advances straight to READY_TO_FILL. {@code APPROVED}/{@code DENIED} reflect an
     * actual BridgeFund decision: both still advance to READY_TO_FILL (assistance is
     * supplementary, never a fulfillment blocker), passing through FINANCIAL_ASSISTANCE_REVIEW
     * so the case history shows the review happened; an approval also records the secured
     * amount. If the referral has already moved past PRIOR_AUTH_APPROVED (a rare race), this is
     * a benign no-op that reports the current status instead of failing the agent's run.
     */
    public String recordFinancialAssistanceDecision(AgentName agent, UUID recommendationId, UUID referralId,
            String decision, java.math.BigDecimal securedAmount, String note) {
        String normalized = decision == null ? "" : decision.toUpperCase();
        if (!Set.of("NOT_REQUIRED", "APPROVED", "DENIED").contains(normalized)) {
            throw ApiException.badRequest("INVALID_DECISION",
                    "decision must be NOT_REQUIRED, APPROVED, or DENIED.", Map.of("decision", String.valueOf(decision)));
        }
        return once(recommendationId, "record_financial_assistance_decision", () -> {
            ReferralRepository.State state = referrals.loadState(referralId)
                    .orElseThrow(() -> ApiException.notFound("Referral", referralId));
            Map<String, Object> out = new LinkedHashMap<>();
            if (!ReferralStatus.PRIOR_AUTH_APPROVED.name().equals(state.currentStatus())) {
                out.put("applied", false);
                out.put("currentStatus", state.currentStatus());
                out.put("reason", "Referral is no longer awaiting a financial assistance decision.");
                return out;
            }
            if ("NOT_REQUIRED".equals(normalized)) {
                referralService.transition(referralId, ReferralStatus.READY_TO_FILL.name(), agent.actorId(), note);
            } else {
                referralService.transition(referralId, ReferralStatus.FINANCIAL_ASSISTANCE_REVIEW.name(),
                        agent.actorId(), note);
                if (securedAmount != null && securedAmount.signum() > 0) {
                    referrals.updateFinancialAmounts(referralId, null, securedAmount, null, time.now());
                }
                referralService.transition(referralId, ReferralStatus.READY_TO_FILL.name(), agent.actorId(),
                        "APPROVED".equals(normalized)
                                ? "Assistance secured; proceeding to fill."
                                : "No assistance secured; proceeding to fill.");
            }
            out.put("applied", true);
            out.put("newStatus", ReferralStatus.READY_TO_FILL.name());
            if (securedAmount != null && securedAmount.signum() > 0) {
                out.put("securedAmount", securedAmount);
            }
            return out;
        });
    }

    /**
     * Exactly-once wrapper: executes the action and claims the ledger slot in one transaction;
     * a replay (or a lost concurrent race) returns the stored result of the original execution.
     */
    private String once(UUID recommendationId, String toolName, Supplier<Map<String, Object>> action) {
        if (recommendationId == null) {
            throw ApiException.badRequest("MISSING_FIELD", "recommendationId is required.",
                    Map.of("field", "recommendationId"));
        }
        var replay = ledger.storedResult(recommendationId, toolName);
        if (replay.isPresent()) {
            log.info("mcp_tool replay tool={} recommendation={}", toolName, recommendationId);
            return replay.get();
        }
        try {
            tx.execute(status -> {
                String result = toJson(action.get());
                if (!ledger.claim(recommendationId, toolName, result, time.now())) {
                    throw new LedgerRaceLost();
                }
                return result;
            });
            log.info("mcp_tool applied tool={} recommendation={}", toolName, recommendationId);
            // Return the ledgered (jsonb-normalized) form so replays are byte-identical.
            return ledger.storedResult(recommendationId, toolName)
                    .orElseThrow(() -> new IllegalStateException("Ledger row vanished after claim"));
        } catch (LedgerRaceLost e) {
            return ledger.storedResult(recommendationId, toolName)
                    .orElseThrow(() -> ApiException.conflict("TOOL_CALL_RACE",
                            "Concurrent duplicate tool call lost its result.", Map.of("tool", toolName)));
        }
    }

    private String toJson(Map<String, Object> result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Tool result not serializable", e);
        }
    }

    private static UUID deterministicId(UUID recommendationId, String suffix) {
        return UUID.nameUUIDFromBytes((recommendationId + "/" + suffix).getBytes());
    }

    private static final class LedgerRaceLost extends RuntimeException {
    }
}
