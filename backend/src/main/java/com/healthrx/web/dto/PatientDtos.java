package com.healthrx.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.healthrx.web.dto.CommonDtos.EntityRef;
import com.healthrx.web.dto.CommonDtos.NamedRef;
import com.healthrx.web.dto.CommonDtos.TaskSummary;

/** Patient workbench response shapes. See api-contracts.md. */
public final class PatientDtos {

    private PatientDtos() {
    }

    public record TherapySummary(
            UUID id,
            EntityRef medication,
            String status,
            LocalDate startDate,
            LocalDate currentRefillDueDate,
            Integer adherencePdcPercent,
            String refillRiskLevel,
            List<String> refillRiskReasons) {
    }

    public record OutreachItem(
            UUID id,
            UUID referralId,
            NamedRef owner,
            String channel,
            String outcome,
            Instant occurredAt,
            String notes) {
    }

    public record InterventionItem(
            UUID id,
            UUID referralId,
            NamedRef owner,
            String interventionType,
            String summary,
            Instant occurredAt) {
    }

    /** Patient directory row: identity plus a workload/risk rollup. */
    public record Summary(
            UUID id,
            String demoMrn,
            String displayName,
            LocalDate dateOfBirth,
            String diseaseState,
            EntityRef clinic,
            EntityRef payer,
            NamedRef primaryOwner,
            long activeReferralCount,
            long openTaskCount,
            int therapyCount,
            String highestRefillRiskLevel) {
    }

    public record Detail(
            UUID id,
            String demoMrn,
            String displayName,
            LocalDate dateOfBirth,
            String diseaseState,
            EntityRef clinic,
            EntityRef payer,
            NamedRef primaryOwner,
            List<TherapySummary> therapies,
            List<TaskSummary> openTasks,
            List<OutreachItem> recentOutreach,
            List<InterventionItem> recentInterventions,
            int pendingAgentRecommendations) {
    }

    public record OutreachResult(
            UUID id,
            UUID patientId,
            UUID referralId,
            NamedRef owner,
            String channel,
            String outcome,
            Instant occurredAt,
            String notes) {
    }

    public record InterventionResult(
            UUID id,
            UUID patientId,
            UUID referralId,
            NamedRef owner,
            String interventionType,
            String summary,
            Instant occurredAt) {
    }
}
