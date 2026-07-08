package com.healthrx.metric;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.healthrx.domain.InterventionType;
import com.healthrx.domain.OutreachOutcome;
import com.healthrx.domain.RefillRiskLevel;
import com.healthrx.domain.TaskType;
import com.healthrx.metric.MetricCalculations.FillCoverage;

/**
 * Computes the per-active-therapy refill risk level and human-readable reasons.
 * Pure logic; see metric-definitions.md (Refill Risk) including the outreach-resolution clause.
 */
public final class RefillRiskCalculator {

    private RefillRiskCalculator() {
    }

    public record OutreachPoint(Instant occurredAt, OutreachOutcome outcome) {
    }

    public record InterventionPoint(Instant occurredAt, InterventionType type) {
    }

    /** An open (OPEN/IN_PROGRESS) task relevant to risk, with its due time. */
    public record TaskPoint(TaskType type, Instant dueAt) {
    }

    public record Inputs(
            LocalDate therapyStartDate,
            LocalDate currentRefillDueDate,
            List<FillCoverage> dispensedFills,
            List<OutreachPoint> outreach,
            List<InterventionPoint> interventions,
            List<TaskPoint> openTasks) {
    }

    public record Result(RefillRiskLevel level, List<String> reasons, Integer pdcPercent, LocalDate currentRefillDueDate) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public static Result compute(Instant now, LocalDate today, Inputs in) {
        Integer pdc = MetricCalculations.pdcPercent(in.therapyStartDate(), in.dispensedFills(), today);
        LocalDate due = in.currentRefillDueDate();

        List<String> highReasons = new ArrayList<>();

        boolean overdue = due != null && due.isBefore(today);
        if (overdue) {
            highReasons.add("Refill overdue since " + DATE.format(due));
        }
        if (pdc != null && pdc < 80) {
            highReasons.add("Adherence below 80% (PDC " + pdc + "%)");
        }
        if (unsuccessfulOutreachUnresolved(now, in)) {
            highReasons.add("Two or more unsuccessful outreach attempts in the last 14 days");
        }

        if (!highReasons.isEmpty()) {
            return new Result(RefillRiskLevel.HIGH, List.copyOf(highReasons), pdc, due);
        }

        List<String> mediumReasons = new ArrayList<>();
        if (due != null && !due.isBefore(today) && !due.isAfter(today.plusDays(7))) {
            mediumReasons.add("Refill due within 7 days");
        }
        if (pdc != null && pdc >= 80 && pdc <= 89) {
            mediumReasons.add("Adherence 80–89% (PDC " + pdc + "%)");
        }
        if (hasContactTaskDueSoon(now, in)) {
            mediumReasons.add("Open refill/contact task due within 3 days");
        }

        if (!mediumReasons.isEmpty()) {
            return new Result(RefillRiskLevel.MEDIUM, List.copyOf(mediumReasons), pdc, due);
        }

        return new Result(RefillRiskLevel.LOW, List.of(), pdc, due);
    }

    private static boolean unsuccessfulOutreachUnresolved(Instant now, Inputs in) {
        Instant cutoff = now.minus(14, java.time.temporal.ChronoUnit.DAYS);
        List<OutreachPoint> recentBad = new ArrayList<>();
        for (OutreachPoint o : in.outreach()) {
            if (o.occurredAt() != null && !o.occurredAt().isBefore(cutoff) && o.outcome().isUnsuccessful()) {
                recentBad.add(o);
            }
        }
        if (recentBad.size() < 2) {
            return false;
        }
        // Resolved if a REACHED outreach or a resolving intervention occurred after the most
        // recent qualifying unsuccessful outreach and within the last 14 days.
        Instant lastBad = recentBad.stream()
                .map(OutreachPoint::occurredAt)
                .max(Comparator.naturalOrder())
                .orElse(null);

        // Resolved by a REACHED outreach or a resolving intervention that is at-or-after the most
        // recent unsuccessful outreach (>= so it still resolves when events share an instant, e.g.
        // a paused simulated clock) and within the 14-day window.
        boolean resolvedByOutreach = in.outreach().stream().anyMatch(o ->
                o.outcome() == OutreachOutcome.REACHED
                        && o.occurredAt() != null
                        && !o.occurredAt().isBefore(cutoff)
                        && lastBad != null && !o.occurredAt().isBefore(lastBad));

        boolean resolvedByIntervention = in.interventions().stream().anyMatch(iv ->
                iv.type() != null && iv.type().resolvesOutreachRisk()
                        && iv.occurredAt() != null
                        && !iv.occurredAt().isBefore(cutoff)
                        && lastBad != null && !iv.occurredAt().isBefore(lastBad));

        return !(resolvedByOutreach || resolvedByIntervention);
    }

    private static boolean hasContactTaskDueSoon(Instant now, Inputs in) {
        Instant horizon = now.plus(3, java.time.temporal.ChronoUnit.DAYS);
        return in.openTasks().stream().anyMatch(t ->
                (t.type() == TaskType.REFILL_FOLLOW_UP || t.type() == TaskType.PATIENT_CONTACT)
                        && t.dueAt() != null && !t.dueAt().isAfter(horizon));
    }
}
