package com.shields.healthrx.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shields.healthrx.domain.InterventionType;
import com.shields.healthrx.domain.OutreachOutcome;
import com.shields.healthrx.domain.RefillRiskLevel;
import com.shields.healthrx.domain.TaskType;
import com.shields.healthrx.metric.MetricCalculations.FillCoverage;
import com.shields.healthrx.metric.RefillRiskCalculator.Inputs;
import com.shields.healthrx.metric.RefillRiskCalculator.InterventionPoint;
import com.shields.healthrx.metric.RefillRiskCalculator.OutreachPoint;
import com.shields.healthrx.metric.RefillRiskCalculator.Result;
import com.shields.healthrx.metric.RefillRiskCalculator.TaskPoint;

class RefillRiskCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-06-29T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-06-29");
    private static final LocalDate START = TODAY.minusDays(120);

    /** Fully-covered fills => PDC 100, so adherence never trips risk on its own. */
    private List<FillCoverage> goodFills() {
        return List.of(
                new FillCoverage(TODAY.minusDays(90), 30),
                new FillCoverage(TODAY.minusDays(60), 30),
                new FillCoverage(TODAY.minusDays(30), 35));
    }

    @Test
    void overdueRefillIsHigh() {
        Inputs in = new Inputs(START, TODAY.minusDays(3), goodFills(), List.of(), List.of(), List.of());
        Result r = RefillRiskCalculator.compute(NOW, TODAY, in);
        assertThat(r.level()).isEqualTo(RefillRiskLevel.HIGH);
        assertThat(r.reasons()).anyMatch(s -> s.contains("overdue"));
    }

    @Test
    void twoUnsuccessfulOutreachesAreHighUnlessResolved() {
        List<OutreachPoint> bad = List.of(
                new OutreachPoint(NOW.minus(5, ChronoUnit.DAYS), OutreachOutcome.NO_ANSWER),
                new OutreachPoint(NOW.minus(2, ChronoUnit.DAYS), OutreachOutcome.LEFT_MESSAGE));
        Inputs in = new Inputs(START, TODAY.plusDays(20), goodFills(), bad, List.of(), List.of());
        assertThat(RefillRiskCalculator.compute(NOW, TODAY, in).level()).isEqualTo(RefillRiskLevel.HIGH);

        // Resolved by a later REACHED outreach.
        List<OutreachPoint> resolved = List.of(
                new OutreachPoint(NOW.minus(5, ChronoUnit.DAYS), OutreachOutcome.NO_ANSWER),
                new OutreachPoint(NOW.minus(2, ChronoUnit.DAYS), OutreachOutcome.LEFT_MESSAGE),
                new OutreachPoint(NOW.minus(1, ChronoUnit.DAYS), OutreachOutcome.REACHED));
        Inputs resolvedIn = new Inputs(START, TODAY.plusDays(20), goodFills(), resolved, List.of(), List.of());
        assertThat(RefillRiskCalculator.compute(NOW, TODAY, resolvedIn).level()).isEqualTo(RefillRiskLevel.LOW);

        // Resolved by an adherence intervention.
        List<InterventionPoint> iv = List.of(
                new InterventionPoint(NOW.minus(1, ChronoUnit.DAYS), InterventionType.ADHERENCE_COUNSELING));
        Inputs ivIn = new Inputs(START, TODAY.plusDays(20), goodFills(), bad, iv, List.of());
        assertThat(RefillRiskCalculator.compute(NOW, TODAY, ivIn).level()).isEqualTo(RefillRiskLevel.LOW);
    }

    @Test
    void sameInstantReachedOutreachResolves() {
        // All events at the same instant (e.g. a paused simulated clock): a REACHED outreach
        // at-or-after the unsuccessful ones must still resolve the outreach risk.
        List<OutreachPoint> events = List.of(
                new OutreachPoint(NOW, OutreachOutcome.NO_ANSWER),
                new OutreachPoint(NOW, OutreachOutcome.LEFT_MESSAGE),
                new OutreachPoint(NOW, OutreachOutcome.REACHED));
        Inputs in = new Inputs(START, TODAY.plusDays(20), goodFills(), events, List.of(), List.of());
        assertThat(RefillRiskCalculator.compute(NOW, TODAY, in).level()).isEqualTo(RefillRiskLevel.LOW);
    }

    @Test
    void refillDueWithinSevenDaysIsMedium() {
        Inputs in = new Inputs(START, TODAY.plusDays(5), goodFills(), List.of(), List.of(), List.of());
        Result r = RefillRiskCalculator.compute(NOW, TODAY, in);
        assertThat(r.level()).isEqualTo(RefillRiskLevel.MEDIUM);
        assertThat(r.reasons()).contains("Refill due within 7 days");
    }

    @Test
    void contactTaskDueSoonIsMedium() {
        List<TaskPoint> tasks = List.of(new TaskPoint(TaskType.REFILL_FOLLOW_UP, NOW.plus(2, ChronoUnit.DAYS)));
        Inputs in = new Inputs(START, TODAY.plusDays(40), goodFills(), List.of(), List.of(), tasks);
        assertThat(RefillRiskCalculator.compute(NOW, TODAY, in).level()).isEqualTo(RefillRiskLevel.MEDIUM);
    }

    @Test
    void noFactorsIsLow() {
        Inputs in = new Inputs(START, TODAY.plusDays(40), goodFills(), List.of(), List.of(), List.of());
        Result r = RefillRiskCalculator.compute(NOW, TODAY, in);
        assertThat(r.level()).isEqualTo(RefillRiskLevel.LOW);
        assertThat(r.reasons()).isEmpty();
    }
}
