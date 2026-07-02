package com.shields.healthrx.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.InterventionType;
import com.shields.healthrx.domain.OutreachChannel;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.domain.SystemActors;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.messaging.EventEnvelope;
import com.shields.healthrx.repo.ProcessedEventRepository;
import com.shields.healthrx.repo.ReferralRepository;
import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.EnumParsing;

/**
 * Applies a consumed {@link EventEnvelope} by dispatching to the centralized Phase 1 services /
 * Phase 2 write paths, then records idempotency — all in one transaction. Throws {@link ApiException}
 * for unknown or non-applicable events so the consumer can dead-letter them.
 */
@Service
public class EventApplicationService {

    private final ReferralIntakeService intake;
    private final ReferralService referralService;
    private final FillService fillService;
    private final TaskService taskService;
    private final PatientService patientService;
    private final AgentRecommendationService agentRecommendations;
    private final ReferralRepository referrals;
    private final ProcessedEventRepository processed;
    private final AppTime time;

    public EventApplicationService(ReferralIntakeService intake, ReferralService referralService,
            FillService fillService, TaskService taskService, PatientService patientService,
            AgentRecommendationService agentRecommendations,
            ReferralRepository referrals, ProcessedEventRepository processed, AppTime time) {
        this.intake = intake;
        this.referralService = referralService;
        this.fillService = fillService;
        this.taskService = taskService;
        this.patientService = patientService;
        this.agentRecommendations = agentRecommendations;
        this.referrals = referrals;
        this.processed = processed;
        this.time = time;
    }

    @Transactional
    public void apply(EventEnvelope env) {
        WorkflowEventType type = WorkflowEventType.fromWire(env.eventType())
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_EVENT",
                        "Unknown event type: " + env.eventType(), Map.of("eventType", String.valueOf(env.eventType()))));
        // Atomically claim within this transaction; an already-claimed (duplicate) event is a no-op.
        if (!processed.claim(env.eventId(), env.eventType(), env.source(), time.now())) {
            return;
        }
        Map<String, Object> p = env.payload() == null ? Map.of() : env.payload();
        Instant at = env.occurredAt() != null ? env.occurredAt() : time.now();

        switch (type) {
            case REFERRAL_CREATED -> intake.create(
                    reqUuid(p, "referralId"), reqUuid(p, "patientId"), reqUuid(p, "clinicId"),
                    reqUuid(p, "medicationId"), reqUuid(p, "payerId"), reqUuid(p, "ownerId"),
                    reqStr(p, "priority"), bool(p, "paRequired", false),
                    bool(p, "financialAssistanceRequired", false), at);
            case BENEFITS_INVESTIGATION_STARTED -> transition(p, ReferralStatus.BENEFITS_INVESTIGATION);
            case PRIOR_AUTHORIZATION_SUBMITTED -> transition(p, ReferralStatus.PRIOR_AUTH_SUBMITTED);
            case PRIOR_AUTHORIZATION_APPROVED -> transition(p, ReferralStatus.PRIOR_AUTH_APPROVED);
            case PRIOR_AUTHORIZATION_DENIED -> transition(p, ReferralStatus.PRIOR_AUTH_DENIED);
            case FINANCIAL_ASSISTANCE_FOUND -> applyFinancialAssistance(p, at);
            case READY_TO_FILL -> transition(p, ReferralStatus.READY_TO_FILL);
            case DELIVERY_SCHEDULED -> transition(p, ReferralStatus.DELIVERY_SCHEDULED);
            case THERAPY_ACTIVATED -> transition(p, ReferralStatus.ACTIVE_THERAPY);
            case REFERRAL_CANCELLED -> transition(p, ReferralStatus.CANCELLED);
            case PRESCRIPTION_FILLED -> fillService.record(
                    reqUuid(p, "fillId"), reqUuid(p, "patientId"), reqUuid(p, "therapyId"), uuid(p, "referralId"),
                    intg(p, "daysSupply", 30), date(p, "dispensedAt", LocalDate.ofInstant(at, ZoneOffset.UTC)));
            case REFILL_DUE -> taskService.createRefillFollowUp(
                    reqUuid(p, "taskId"), reqUuid(p, "patientId"), uuid(p, "referralId"), reqUuid(p, "ownerId"),
                    instant(p, "dueAt", at), str(p, "title"));
            case REFILL_MISSED -> fillService.markMissed(
                    reqUuid(p, "fillId"), reqUuid(p, "patientId"), reqUuid(p, "therapyId"), uuid(p, "referralId"),
                    date(p, "expectedRefillDate", LocalDate.ofInstant(at, ZoneOffset.UTC)), intg(p, "daysSupply", 30));
            case PATIENT_OUTREACH_LOGGED -> patientService.logOutreach(
                    reqUuid(p, "patientId"), uuid(p, "referralId"), actor(p),
                    EnumParsing.require(OutreachChannel.class, reqStr(p, "channel"), "channel"),
                    EnumParsing.require(OutreachOutcome.class, reqStr(p, "outcome"), "outcome"),
                    instant(p, "occurredAt", at), str(p, "notes"));
            case CLINICAL_INTERVENTION_CREATED -> patientService.logIntervention(
                    reqUuid(p, "patientId"), uuid(p, "referralId"), actor(p),
                    EnumParsing.require(InterventionType.class, reqStr(p, "interventionType"), "interventionType"),
                    reqStr(p, "summary"), instant(p, "occurredAt", at));
            case AGENT_RECOMMENDATION_CREATED -> agentRecommendations.recordCreated(p, at);
            case AGENT_RECOMMENDATION_APPLIED -> agentRecommendations.recordApplied(p, at);
        }
    }

    private void transition(Map<String, Object> p, ReferralStatus target) {
        UUID id = reqUuid(p, "referralId");
        ReferralRepository.State state = referrals.loadState(id)
                .orElseThrow(() -> ApiException.notFound("Referral", id));
        if (target.name().equals(state.currentStatus())) {
            return; // already in target status: idempotent no-op (tolerates redelivery / out-of-order)
        }
        referralService.transition(id, target.name(), SystemActors.SYSTEM, str(p, "note"));
    }

    /** Records financial-assistance amounts; transitions into review only when legally allowed. */
    private void applyFinancialAssistance(Map<String, Object> p, Instant at) {
        UUID id = reqUuid(p, "referralId");
        ReferralRepository.State state = referrals.loadState(id)
                .orElseThrow(() -> ApiException.notFound("Referral", id));
        if (ReferralStatus.valueOf(state.currentStatus())
                .canTransitionTo(ReferralStatus.FINANCIAL_ASSISTANCE_REVIEW)) {
            referralService.transition(id, ReferralStatus.FINANCIAL_ASSISTANCE_REVIEW.name(),
                    SystemActors.SYSTEM, str(p, "note"));
        }
        applyFinancials(p, at);
    }

    private void applyFinancials(Map<String, Object> p, Instant at) {
        BigDecimal copay = bigDecimal(p, "copayAmount");
        BigDecimal secured = bigDecimal(p, "securedAmount");
        Boolean required = p.containsKey("financialAssistanceRequired")
                ? bool(p, "financialAssistanceRequired", false) : null;
        if (copay != null || secured != null || required != null) {
            referrals.updateFinancialAmounts(reqUuid(p, "referralId"), copay, secured, required, at);
        }
    }

    private static UUID actor(Map<String, Object> p) {
        UUID owner = uuid(p, "ownerId");
        return owner != null ? owner : SystemActors.SYSTEM;
    }

    // --- payload readers ---

    private static UUID uuid(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : UUID.fromString(v.toString());
    }

    private static UUID reqUuid(Map<String, Object> p, String key) {
        UUID v = uuid(p, key);
        if (v == null) {
            throw missing(key);
        }
        return v;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String reqStr(Map<String, Object> p, String key) {
        String v = str(p, key);
        if (v == null || v.isBlank()) {
            throw missing(key);
        }
        return v;
    }

    private static boolean bool(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        return v == null ? def : Boolean.parseBoolean(v.toString());
    }

    private static int intg(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v == null) {
            return def;
        }
        return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
    }

    private static BigDecimal bigDecimal(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static LocalDate date(Map<String, Object> p, String key, LocalDate def) {
        Object v = p.get(key);
        return v == null ? def : LocalDate.parse(v.toString());
    }

    private static Instant instant(Map<String, Object> p, String key, Instant def) {
        Object v = p.get(key);
        return v == null ? def : Instant.parse(v.toString());
    }

    private static ApiException missing(String field) {
        return ApiException.unprocessable("MISSING_FIELD", "Missing required event field: " + field,
                Map.of("field", field));
    }
}
