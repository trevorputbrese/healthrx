package com.shields.healthrx.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.shields.healthrx.metric.MetricCalculations.FillCoverage;

class MetricCalculationsTest {

    @Test
    void medianHandlesOddEvenEmpty() {
        assertThat(MetricCalculations.median(List.of(5.0))).isEqualTo(5.0);
        assertThat(MetricCalculations.median(List.of(1.0, 3.0, 2.0))).isEqualTo(2.0);
        assertThat(MetricCalculations.median(List.of(1.0, 2.0, 3.0, 4.0))).isEqualTo(2.5);
        assertThat(MetricCalculations.median(List.of())).isNull();
    }

    @Test
    void daysBetweenRoundsToOneDecimal() {
        Instant from = Instant.parse("2026-06-20T00:00:00Z");
        Instant to = Instant.parse("2026-06-29T12:00:00Z");
        assertThat(MetricCalculations.daysBetween(from, to)).isEqualTo(9.5);
    }

    @Test
    void averagePercentRoundsToWhole() {
        assertThat(MetricCalculations.averagePercent(List.of(80, 90, 91))).isEqualTo(87);
        assertThat(MetricCalculations.averagePercent(List.of())).isNull();
    }

    @Test
    void pdcReturnsNullWhenDenominatorUnderFourteenDays() {
        LocalDate today = LocalDate.parse("2026-06-29");
        LocalDate start = today.minusDays(10);
        assertThat(MetricCalculations.pdcPercent(start, List.of(new FillCoverage(start, 30)), today)).isNull();
    }

    @Test
    void pdcFullyCoveredIsHundred() {
        LocalDate today = LocalDate.parse("2026-06-29");
        LocalDate start = today.minusDays(90);
        // Three consecutive 30-day fills cover the entire window.
        List<FillCoverage> fills = List.of(
                new FillCoverage(start, 30),
                new FillCoverage(start.plusDays(30), 30),
                new FillCoverage(start.plusDays(60), 30));
        assertThat(MetricCalculations.pdcPercent(start, fills, today)).isEqualTo(100);
    }

    @Test
    void pdcWithGapAndOverlapMergesCoverage() {
        LocalDate today = LocalDate.parse("2026-06-29");
        LocalDate start = today.minusDays(100); // window clips to 90 days
        // Overlapping first two fills + a gap before the third.
        List<FillCoverage> fills = List.of(
                new FillCoverage(today.minusDays(90), 30),
                new FillCoverage(today.minusDays(75), 30), // overlaps previous
                new FillCoverage(today.minusDays(20), 30)); // covers to +10 past today, clipped
        // covered: [-90,-45) = 45 days, then [-20, today) clipped = 20 days => 65 / 90
        Integer pdc = MetricCalculations.pdcPercent(start, fills, today);
        assertThat(pdc).isEqualTo((int) Math.round(65.0 / 90.0 * 100));
    }

    @Test
    void pdcIsCappedAtHundred() {
        LocalDate today = LocalDate.parse("2026-06-29");
        LocalDate start = today.minusDays(30);
        // Oversupply beyond window should never exceed 100.
        assertThat(MetricCalculations.pdcPercent(start, List.of(new FillCoverage(start, 365)), today)).isEqualTo(100);
    }
}
