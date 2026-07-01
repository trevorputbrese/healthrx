package com.shields.healthrx.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.InterventionType;
import com.shields.healthrx.domain.OutreachChannel;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.domain.WorkflowEventType;
import com.shields.healthrx.metric.RefillRiskCalculator;
import com.shields.healthrx.repo.CareTeamRepository;
import com.shields.healthrx.repo.PatientRepository;
import com.shields.healthrx.repo.PatientRepository.HeaderRow;
import com.shields.healthrx.repo.RiskDataRepository;
import com.shields.healthrx.repo.RiskDataRepository.ActiveTherapyRow;
import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.dto.CommonDtos.EntityRef;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.PatientDtos;
import com.shields.healthrx.web.dto.TimelineDtos;

/** Patient workbench detail, journey timeline, and outreach/intervention logging. */
@Service
public class PatientService {

    private final PatientRepository patients;
    private final RiskDataRepository riskData;
    private final RefillRiskService riskService;
    private final CareTeamRepository careTeam;
    private final EventLog events;
    private final AppTime time;

    public PatientService(PatientRepository patients, RiskDataRepository riskData, RefillRiskService riskService,
                          CareTeamRepository careTeam, EventLog events, AppTime time) {
        this.patients = patients;
        this.riskData = riskData;
        this.riskService = riskService;
        this.careTeam = careTeam;
        this.events = events;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public PatientDtos.Detail detail(UUID patientId) {
        HeaderRow h = patients.findHeader(patientId)
                .orElseThrow(() -> ApiException.notFound("Patient", patientId));

        List<ActiveTherapyRow> therapies = riskData.therapiesForPatient(patientId);
        Map<UUID, RefillRiskCalculator.Result> risk = riskService.compute(therapies);

        List<PatientDtos.TherapySummary> therapySummaries = therapies.stream().map(t -> {
            RefillRiskCalculator.Result r = risk.get(t.therapyId());
            return new PatientDtos.TherapySummary(
                    t.therapyId(), new EntityRef(t.medicationId(), t.medicationName()), t.status(),
                    t.startDate(),
                    r != null ? r.currentRefillDueDate() : t.currentRefillDueDate(),
                    r != null ? r.pdcPercent() : null,
                    r != null ? r.level().name() : null,
                    r != null ? r.reasons() : List.of());
        }).toList();

        return new PatientDtos.Detail(
                h.id(), h.demoMrn(), h.displayName(), h.dateOfBirth(), h.diseaseState(),
                new EntityRef(h.clinicId(), h.clinicName()),
                new EntityRef(h.payerId(), h.payerName()),
                new NamedRef(h.ownerId(), h.ownerName()),
                therapySummaries,
                patients.openTasksForPatient(patientId),
                patients.recentOutreach(patientId, 10),
                patients.recentInterventions(patientId, 10));
    }

    @Transactional(readOnly = true)
    public TimelineDtos.Response timeline(UUID patientId, Set<String> types, int limit) {
        if (!patients.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }
        List<TimelineDtos.Item> all = new ArrayList<>();
        all.addAll(patients.timelineStatusHistory(patientId));
        all.addAll(patients.timelineFills(patientId));
        all.addAll(patients.timelineTasks(patientId));
        all.addAll(patients.timelineOutreach(patientId));
        all.addAll(patients.timelineInterventions(patientId));
        all.addAll(patients.timelineNotes(patientId));

        List<TimelineDtos.Item> filtered = all.stream()
                .filter(item -> item.occurredAt() != null)
                .filter(item -> types == null || types.isEmpty() || types.contains(item.type()))
                .sorted(Comparator.comparing(TimelineDtos.Item::occurredAt).reversed())
                .limit(Math.min(Math.max(limit, 1), 200))
                .toList();
        return new TimelineDtos.Response(filtered);
    }

    @Transactional
    public PatientDtos.OutreachResult logOutreach(UUID patientId, UUID referralId, UUID ownerId,
            OutreachChannel channel, OutreachOutcome outcome, Instant occurredAt, String notes) {
        if (!patients.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }
        NamedRef owner = careTeam.requireActiveActor(ownerId);
        Instant now = time.now();
        Instant occurred = occurredAt != null ? occurredAt : now;
        UUID id = UUID.randomUUID();
        patients.insertOutreach(id, patientId, referralId, ownerId, channel, outcome, occurred, notes, now);
        events.emit(WorkflowEventType.PATIENT_OUTREACH_LOGGED, referralId, patientId,
                "channel=" + channel + " outcome=" + outcome);
        return new PatientDtos.OutreachResult(id, patientId, referralId, owner,
                channel.name(), outcome.name(), occurred, notes);
    }

    @Transactional
    public PatientDtos.InterventionResult logIntervention(UUID patientId, UUID referralId, UUID ownerId,
            InterventionType interventionType, String summary, Instant occurredAt) {
        if (!patients.exists(patientId)) {
            throw ApiException.notFound("Patient", patientId);
        }
        NamedRef owner = careTeam.requireActiveActor(ownerId);
        Instant now = time.now();
        Instant occurred = occurredAt != null ? occurredAt : now;
        UUID id = UUID.randomUUID();
        patients.insertIntervention(id, patientId, referralId, ownerId, interventionType.name(), summary, occurred, now);
        events.emit(WorkflowEventType.CLINICAL_INTERVENTION_CREATED, referralId, patientId,
                "type=" + interventionType);
        return new PatientDtos.InterventionResult(id, patientId, referralId, owner,
                interventionType.name(), summary, occurred);
    }
}
