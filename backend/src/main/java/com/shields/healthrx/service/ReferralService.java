package com.shields.healthrx.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.metric.MetricCalculations;
import com.shields.healthrx.metric.RefillRiskCalculator;
import com.shields.healthrx.repo.CareTeamRepository;
import com.shields.healthrx.repo.QueueFilter;
import com.shields.healthrx.repo.ReferralRepository;
import com.shields.healthrx.repo.ReferralRepository.DetailRow;
import com.shields.healthrx.repo.ReferralRepository.QueueRow;
import com.shields.healthrx.repo.ReferralRepository.State;
import com.shields.healthrx.repo.ReferralRepository.StatusHistoryInsert;
import com.shields.healthrx.repo.TherapyRepository;
import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.dto.CommonDtos.EntityRef;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.CommonDtos.PatientRef;
import com.shields.healthrx.web.dto.PageResponse;
import com.shields.healthrx.web.dto.ReferralDtos;

/** Referral queue, detail, status transitions, notes, and financials. */
@Service
public class ReferralService {

    private final ReferralRepository referrals;
    private final CareTeamRepository careTeam;
    private final TherapyRepository therapies;
    private final RefillRiskService riskService;
    private final EventLog events;
    private final AppTime time;

    public ReferralService(ReferralRepository referrals, CareTeamRepository careTeam, TherapyRepository therapies,
                           RefillRiskService riskService, EventLog events, AppTime time) {
        this.referrals = referrals;
        this.careTeam = careTeam;
        this.therapies = therapies;
        this.riskService = riskService;
        this.events = events;
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
                referrals.statusHistory(id));
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

        WorkflowEventType event = to.eventOnEnter();
        UUID historyId = UUID.randomUUID();
        referrals.insertStatusHistory(new StatusHistoryInsert(
                historyId, id, from.name(), to.name(), now, changedById, note, event.wireName()));
        events.emit(event, id, null, "from=" + from.name() + " to=" + to.name());

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
