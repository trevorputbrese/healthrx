package com.shields.healthrx.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.shields.healthrx.web.dto.CommonDtos.EntityRef;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.CommonDtos.NoteItem;
import com.shields.healthrx.web.dto.CommonDtos.PatientRef;
import com.shields.healthrx.web.dto.CommonDtos.TaskSummary;

/** Referral queue and detail response shapes. See api-contracts.md. */
public final class ReferralDtos {

    private ReferralDtos() {
    }

    /** Queue row. */
    public record Summary(
            UUID id,
            String referralNumber,
            PatientRef patient,
            EntityRef clinic,
            EntityRef medication,
            EntityRef payer,
            NamedRef owner,
            String currentStatus,
            String priority,
            Instant receivedAt,
            Double daysSinceReceived,
            Double priorAuthorizationAgeDays,
            Double timeToTherapyDays,
            BigDecimal copayAmount,
            BigDecimal financialAssistanceSecuredAmount,
            long openTaskCount,
            String refillRiskLevel) {
    }

    public record Milestones(
            Instant benefitsInvestigationStartedAt,
            Instant paSubmittedAt,
            Instant paDecidedAt,
            Instant readyToFillAt,
            Instant deliveryScheduledAt,
            Instant activeTherapyAt) {
    }

    public record Financials(
            BigDecimal copayAmount,
            boolean financialAssistanceRequired,
            BigDecimal financialAssistanceSecuredAmount) {
    }

    public record Metrics(
            Double daysSinceReceived,
            Double priorAuthorizationAgeDays,
            Double timeToTherapyDays) {
    }

    public record StatusHistoryItem(
            UUID id,
            String fromStatus,
            String toStatus,
            Instant changedAt,
            NamedRef changedBy,
            String note) {
    }

    public record PatientLite(UUID id, String displayName, LocalDate dateOfBirth, String diseaseState) {
    }

    public record MedicationLite(UUID id, String name, String route) {
    }

    public record PayerLite(UUID id, String name, String payerType) {
    }

    public record Detail(
            UUID id,
            String referralNumber,
            PatientLite patient,
            EntityRef clinic,
            MedicationLite medication,
            PayerLite payer,
            NamedRef owner,
            String currentStatus,
            List<String> allowedNextStatuses,
            String priority,
            Instant receivedAt,
            Milestones milestones,
            Financials financials,
            Metrics metrics,
            List<TaskSummary> openTasks,
            List<NoteItem> recentNotes,
            List<StatusHistoryItem> statusHistory,
            int pendingAgentRecommendations) {
    }

    public record TransitionResult(
            UUID id,
            String currentStatus,
            List<String> allowedNextStatuses,
            StatusHistoryItem statusHistoryItem) {
    }

    public record NoteResult(UUID id, UUID referralId, NamedRef author, String body, Instant createdAt) {
    }

    public record FinancialsResult(UUID id, Financials financials, Instant updatedAt) {
    }
}
