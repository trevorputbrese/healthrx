package com.shields.healthrx.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.AgentName;
import com.shields.healthrx.domain.InterventionType;
import com.shields.healthrx.domain.OutreachChannel;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.repo.AgentToolCallRepository;
import com.shields.healthrx.repo.ReferralRepository;
import com.shields.healthrx.repo.TaskRepository;
import com.shields.healthrx.repo.TherapyRepository;
import com.shields.healthrx.service.FillService;
import com.shields.healthrx.service.PatientService;
import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.EnumParsing;

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
    private final AppTime time;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public AgentActionService(AgentToolCallRepository ledger, PatientService patients, FillService fills,
            TaskRepository tasks, TherapyRepository therapies, ReferralRepository referrals,
            AppTime time, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.patients = patients;
        this.fills = fills;
        this.tasks = tasks;
        this.therapies = therapies;
        this.referrals = referrals;
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
