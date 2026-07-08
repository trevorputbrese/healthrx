package com.healthrx.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.healthrx.web.dto.CommonDtos.NamedRef;

/** Outcomes dashboard shapes. See api-contracts.md. */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record Window(LocalDate from, LocalDate to) {
    }

    public record Tiles(
            long activePatientsOnTherapy,
            Double medianTimeToTherapyDays,
            Double medianPriorAuthorizationTurnaroundDays,
            long refillRiskCount,
            long highRefillRiskCount,
            Integer averageAdherencePdcPercent,
            BigDecimal financialAssistanceSecuredAmount,
            long financialAssistanceSecuredCount,
            long overdueTaskCount) {
    }

    public record StatusCount(String status, long count) {
    }

    public record OwnerTaskCount(NamedRef owner, long count) {
    }

    public record Summary(
            Window window,
            Tiles tiles,
            List<StatusCount> statusCounts,
            List<OwnerTaskCount> openTasksByOwner) {
    }

    public record TrendBucket(
            LocalDate from,
            LocalDate to,
            long referralsReceived,
            long activatedTherapies,
            Double medianTimeToTherapyDays,
            Double medianPriorAuthorizationTurnaroundDays,
            Integer averageAdherencePdcPercent,
            long refillRiskCount) {
    }

    public record Trends(String bucket, List<TrendBucket> series) {
    }
}
