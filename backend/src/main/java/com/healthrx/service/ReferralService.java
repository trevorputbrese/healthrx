package com.healthrx.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.healthrx.config.AppTime;
import com.healthrx.domain.ReferralStatus;
import com.healthrx.domain.SystemActors;
import com.healthrx.domain.WorkflowEventType;
import com.healthrx.messaging.WorkflowEventPublisher;
import com.healthrx.metric.MetricCalculations;
import com.healthrx.metric.RefillRiskCalculator;
import com.healthrx.repo.AgentRecommendationRepository;
import com.healthrx.repo.CareTeamRepository;
import com.healthrx.repo.ProcessedEventRepository;
import com.healthrx.repo.QueueFilter;
import com.healthrx.repo.ReferralRepository;
import com.healthrx.repo.ReferralRepository.DetailRow;
import com.healthrx.repo.ReferralRepository.QueueRow;
import com.healthrx.repo.ReferralRepository.State;
import com.healthrx.repo.ReferralRepository.StatusHistoryInsert;
import com.healthrx.repo.TaskRepository;
import com.healthrx.repo.TherapyRepository;
import com.healthrx.web.ApiException;
import com.healthrx.web.dto.CommonDtos.EntityRef;
import com.healthrx.web.dto.CommonDtos.NamedRef;
import com.healthrx.web.dto.CommonDtos.PatientRef;
import com.healthrx.web.dto.PageResponse;
import com.healthrx.web.dto.ReferralDtos;

/** Referral queue, detail, status transitions, notes, and financials. */
@Service
public class ReferralService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ReferralService.class);

    /** Statuses whose entry is re-broadcast onto the event bus for the agents watching them. */
    private static final java.util.Set<ReferralStatus> RE_BROADCAST_ON_ENTER = java.util.Set.of(
            ReferralStatus.BENEFITS_INVESTIGATION, ReferralStatus.PRIOR_AUTH_SUBMITTED,
            ReferralStatus.PRIOR_AUTH_APPROVED);

    private final ReferralRepository referrals;
    private final CareTeamRepository careTeam;
    private final TherapyRepository therapies;
    private final RefillRiskService riskService;
    private final AgentRecommendationRepository agentRecommendations;
    private final TaskRepository tasks;
    private final EventLog events;
    private final WorkflowEventPublisher eventPublisher;
    private final ProcessedEventRepository processedEvents;
    private final AppTime time;

    public ReferralService(ReferralRepository referrals, CareTeamRepository careTeam, TherapyRepository therapies,
                           RefillRiskService riskService, AgentRecommendationRepository agentRecommendations,
                           TaskRepository tasks, EventLog events,
                           WorkflowEventPublisher eventPublisher,
                           ProcessedEventRepository processedEvents, AppTime time) {
        this.referrals = referrals;
        this.careTeam = careTeam;
        this.therapies = therapies;
        this.riskService = riskService;
        this.agentRecommendations = agentRecommendations;
        this.tasks = tasks;
        this.events = events;
        this.eventPublisher = eventPublisher;
        this.processedEvents = processedEvents;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReferralDtos.Summary> queue(QueueFilter filter) {
        List<QueueRow> rows = referrals.queue(filter);
        long total = referrals.countQueue(filter);
        Instant now = time.now();

        List<UUID> therapyIds = rows.stream().map(QueueRow::therapyId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, RefillRiskCalculator.Result> riskByTherapy = riskService.computeForTherapyIds(therapyIds);

        List<ReferralDtos.Summary> items = rows.stream().map(r -> {
            RefillRiskCalculator.Result risk = r.therapyId() == null ? null : riskByTherapy.get(r.therapyId());
            return new ReferralDtos.Summary(
                    r.id(), r.referralNumber(),
                    new PatientRef(r.patientId(), r.patientName(), r.patientDisease()),
                    new EntityRef(r.clinicId(), r.clinicName()),
                    new EntityRef(r.medicationId(), r.medicationName()),
                    new EntityRef(r.payerId(), r.payerName()),
                    new NamedRef(r.ownerId(), r.ownerName()),
                    r.currentStatus(), r.priority(), r.receivedAt(),
                    MetricCalculations.daysBetween(r.receivedAt(), now),
                    priorAuthAgeDays(r.currentStatus(), r.paSubmittedAt(), r.paDecidedAt(), now),
                    timeToTherapyDays(r.receivedAt(), r.activeTherapyAt()),
                    r.copayAmount(), r.financialAssistanceSecuredAmount(), r.openTaskCount(),
                    risk == null ? null : risk.level().name());
        }).toList();

        return PageResponse.of(items, filter.page(), normalizedSize(filter.size()), total);
    }

    @Transactional(readOnly = true)
    public ReferralDtos.Detail detail(UUID id) {
        DetailRow r = referrals.findDetail(id).orElseThrow(() -> ApiException.notFound("Referral", id));
        Instant now = time.now();
        ReferralStatus status = ReferralStatus.valueOf(r.currentStatus());

        var milestones = new ReferralDtos.Milestones(
                r.benefitsInvestigationStartedAt(), r.paSubmittedAt(), r.paDecidedAt(),
                r.readyToFillAt(), r.deliveryScheduledAt(), r.activeTherapyAt());
        var financials = new ReferralDtos.Financials(
                r.copayAmount(), r.financialAssistanceRequired(), r.financialAssistanceSecuredAmount());
        var metrics = new ReferralDtos.Metrics(
                MetricCalculations.daysBetween(r.receivedAt(), now),
                priorAuthAgeDays(r.currentStatus(), r.paSubmittedAt(), r.paDecidedAt(), now),
                timeToTherapyDays(r.receivedAt(), r.activeTherapyAt()));

        return new ReferralDtos.Detail(
                r.id(), r.referralNumber(),
                new ReferralDtos.PatientLite(r.patientId(), r.patientName(), r.patientDob(), r.patientDisease()),
                new EntityRef(r.clinicId(), r.clinicName()),
                new ReferralDtos.MedicationLite(r.medicationId(), r.medicationName(), r.medicationRoute()),
                new ReferralDtos.PayerLite(r.payerId(), r.payerName(), r.payerType()),
                new NamedRef(r.ownerId(), r.ownerName()),
                r.currentStatus(), names(status.allowedNextStatuses()), r.priority(), r.receivedAt(),
                milestones, financials, metrics,
                referrals.openTasksForReferral(id),
                referrals.recentNotes(id, 10),
                referrals.statusHistory(id),
                agentRecommendations.countPendingForReferral(id));
    }

    @Transactional
    public ReferralDtos.TransitionResult transition(UUID id, String toStatusRaw, UUID changedById, String note) {
        State old = referrals.loadState(id).orElseThrow(() -> ApiException.notFound("Referral", id));
        ReferralStatus from = ReferralStatus.valueOf(old.currentStatus());
        ReferralStatus to = ReferralStatus.tryParse(toStatusRaw)
                .orElseThrow(() -> ApiException.badRequest("UNKNOWN_STATUS",
                        "Unknown target status: " + toStatusRaw, Map.of("toStatus", String.valueOf(toStatusRaw))));
        NamedRef actor = careTeam.requireActiveActor(changedById);

        if (!from.canTransitionTo(to)) {
            throw ApiException.invalidTransition(from.name(), to.name());
        }

        Instant now = time.now();
        State updated = ReferralTransitions.apply(old, from, to, now);
        referrals.updateState(id, updated, now);

        if (to == ReferralStatus.ACTIVE_THERAPY) {
            if (old.therapyId() == null) {
                // Activate a referral that has no therapy yet by creating and linking one.
                UUID therapyId = UUID.randomUUID();
                therapies.createActiveFromReferral(therapyId, id, time.today(), now);
                referrals.linkTherapy(id, therapyId);
            } else {
                therapies.activate(old.therapyId(), time.today());
            }
        }

        // Advancing the workflow settles the work items that asked for it: open tasks on this
        // referral are completed (or cancelled with the referral) so the Tasks queue never
        // holds stale asks the world already moved past.
        int resolved = to == ReferralStatus.CANCELLED
                ? tasks.cancelOpenForReferral(id)
                : tasks.completeOpenForReferral(id, now);
        if (resolved > 0) {
            log.info("referral_tasks_auto_resolved referral={} to={} count={}", id, to, resolved);
        }

        WorkflowEventType event = to.eventOnEnter();
        UUID historyId = UUID.randomUUID();
        referrals.insertStatusHistory(new StatusHistoryInsert(
                historyId, id, from.name(), to.name(), now, changedById, note, event.wireName()));
        events.emit(event, id, null, "from=" + from.name() + " to=" + to.name());

        // Three transitions are re-broadcast onto the event bus whenever a REAL actor (human or
        // agent — anyone but the event consumer replaying an already-published event) causes
        // them: BENEFITS_INVESTIGATION, so the Access Workflow Agent can run the benefits check
        // and submit the prior auth itself; PRIOR_AUTH_SUBMITTED, so the Access Workflow Agent
        // can chase the payer — including when the SUBMISSION came from its own benefits beat;
        // and PRIOR_AUTH_APPROVED, so the Financial Assistance Agent can chase copay assistance —
        // including when the APPROVAL came from the Access Agent recording ClearPath's decision.
        // One human advance into Benefits investigation therefore chains agent-side all the way
        // to Ready to fill. The eventId is pre-claimed in processed_events within THIS
        // transaction so the API's own consumer skips the re-broadcast entirely (late
        // consumption could otherwise regress a referral an agent already decided, e.g. back to
        // PRIOR_AUTH_SUBMITTED via the resubmit edge).
        if (RE_BROADCAST_ON_ENTER.contains(to) && !SystemActors.SYSTEM.equals(changedById)) {
            UUID patientId = referrals.patientOf(id).orElse(null);
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("referralId", id.toString());
            if (patientId != null) {
                payload.put("patientId", patientId.toString());
            }
            UUID eventId = UUID.randomUUID();
            processedEvents.claim(eventId, event.wireName(), eventPublisher.source(), now);
            eventPublisher.publishAfterCommit(eventId, event, now, payload);
        }

        var historyItem = new ReferralDtos.StatusHistoryItem(historyId, from.name(), to.name(), now, actor, note);
        return new ReferralDtos.TransitionResult(id, to.name(), names(to.allowedNextStatuses()), historyItem);
    }

    @Transactional
    public ReferralDtos.NoteResult addNote(UUID referralId, UUID authorId, String body) {
        if (!referrals.exists(referralId)) {
            throw ApiException.notFound("Referral", referralId);
        }
        NamedRef author = careTeam.requireActiveActor(authorId);
        Instant now = time.now();
        UUID id = UUID.randomUUID();
        referrals.insertNote(id, referralId, authorId, body, now);
        return new ReferralDtos.NoteResult(id, referralId, author, body, now);
    }

    @Transactional
    public ReferralDtos.FinancialsResult updateFinancials(UUID id, BigDecimal copayAmount,
            BigDecimal faSecuredAmount, Boolean faRequired, UUID changedById, String note) {
        State old = referrals.loadState(id).orElseThrow(() -> ApiException.notFound("Referral", id));
        careTeam.requireActiveActor(changedById);

        if (copayAmount == null && faSecuredAmount == null && faRequired == null) {
            throw ApiException.badRequest("EMPTY_FINANCIALS",
                    "At least one financial field is required.", Map.of());
        }
        if ((copayAmount != null && copayAmount.signum() < 0)
                || (faSecuredAmount != null && faSecuredAmount.signum() < 0)) {
            throw ApiException.badRequest("NEGATIVE_AMOUNT", "Amounts must be non-negative.", Map.of());
        }

        BigDecimal copay = copayAmount != null ? copayAmount : old.copayAmount();
        BigDecimal secured = faSecuredAmount != null ? faSecuredAmount : old.financialAssistanceSecuredAmount();
        boolean required = faRequired != null ? faRequired : old.financialAssistanceRequired();
        Instant now = time.now();

        State updated = new State(old.currentStatus(), old.therapyId(), old.benefitsInvestigationStartedAt(),
                old.paSubmittedAt(), old.paDecidedAt(), old.readyToFillAt(), old.deliveryScheduledAt(),
                old.activeTherapyAt(), old.closedAt(), required, secured, copay);
        referrals.updateState(id, updated, now);

        boolean securedFound = secured != null && secured.signum() > 0;
        WorkflowEventType event = securedFound ? WorkflowEventType.FINANCIAL_ASSISTANCE_FOUND : null;
        String historyNote = note != null ? note
                : (securedFound ? "Financial assistance recorded: " + secured : "Financial details updated.");
        // from==to row marks a non-transition financial annotation (timeline FINANCIAL item).
        referrals.insertStatusHistory(new StatusHistoryInsert(UUID.randomUUID(), id,
                old.currentStatus(), old.currentStatus(), now, changedById, historyNote,
                event == null ? null : event.wireName()));
        if (securedFound) {
            events.emit(WorkflowEventType.FINANCIAL_ASSISTANCE_FOUND, id, null, "secured=" + secured);
        }

        return new ReferralDtos.FinancialsResult(id,
                new ReferralDtos.Financials(copay, required, secured), now);
    }

    // --- metric helpers ---

    private static Double priorAuthAgeDays(String status, Instant paSubmittedAt, Instant paDecidedAt, Instant now) {
        if (!"PRIOR_AUTH_SUBMITTED".equals(status) || paSubmittedAt == null || paDecidedAt != null) {
            return null;
        }
        return MetricCalculations.daysBetween(paSubmittedAt, now);
    }

    private static Double timeToTherapyDays(Instant receivedAt, Instant activeTherapyAt) {
        if (activeTherapyAt == null) {
            return null;
        }
        return MetricCalculations.daysBetween(receivedAt, activeTherapyAt);
    }

    private static List<String> names(java.util.Collection<ReferralStatus> statuses) {
        return statuses.stream().map(Enum::name).toList();
    }

    private static int normalizedSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
