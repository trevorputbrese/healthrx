package com.healthrx.metric;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure, side-effect-free metric calculations. See metric-definitions.md.
 * Kept free of database/clock dependencies so it is directly unit-testable.
 */
public final class MetricCalculations {

    private MetricCalculations() {
    }

    private static final long MS_PER_DAY = 86_400_000L;

    /** Whole-day-fractional difference between two instants, rounded to one decimal. */
    public static double daysBetween(Instant from, Instant to) {
        double raw = (to.toEpochMilli() - from.toEpochMilli()) / (double) MS_PER_DAY;
        return roundToOneDecimal(raw);
    }

    public static double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /**
     * Median of the values (middle, or average of the two middle values for an even count).
     * Returns {@code null} for an empty list.
     */
    public static Double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return sorted.get(mid);
        }
        return (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }

    /** Median rounded to one decimal (day-based dashboard metric), or null when empty. */
    public static Double medianDays(List<Double> values) {
        Double m = median(values);
        return m == null ? null : roundToOneDecimal(m);
    }

    /** Average rounded to a whole percent, or null when empty. */
    public static Integer averagePercent(List<Integer> percents) {
        if (percents == null || percents.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (int p : percents) {
            sum += p;
        }
        return (int) Math.round(sum / percents.size());
    }

    /** A dispensed fill's coverage span input for PDC. */
    public record FillCoverage(LocalDate dispensedAt, int daysSupply) {
    }

    /**
     * PDC-style adherence over the observation window
     * {@code [max(therapyStart, today-90), today]}.
     * Returns {@code null} when the denominator is fewer than 14 days (display "new therapy").
     */
    public static Integer pdcPercent(LocalDate therapyStart, List<FillCoverage> dispensedFills, LocalDate today) {
        LocalDate ninetyBack = today.minusDays(90);
        LocalDate windowStart = therapyStart != null && therapyStart.isAfter(ninetyBack) ? therapyStart : ninetyBack;
        if (!windowStart.isBefore(today)) {
            return null;
        }
        long denominatorDays = ChronoUnit.DAYS.between(windowStart, today);
        if (denominatorDays < 14) {
            return null;
        }
        long covered = mergedCoveredDays(dispensedFills, windowStart, today);
        long pct = Math.round((covered / (double) denominatorDays) * 100.0);
        return (int) Math.min(100, pct);
    }

    /** Union of dispensed-fill coverage intervals, clipped to [windowStart, windowEnd), in days. */
    static long mergedCoveredDays(List<FillCoverage> fills, LocalDate windowStart, LocalDate windowEnd) {
        if (fills == null || fills.isEmpty()) {
            return 0;
        }
        List<long[]> intervals = new ArrayList<>();
        for (FillCoverage f : fills) {
            if (f.dispensedAt() == null) {
                continue;
            }
            LocalDate start = f.dispensedAt();
            LocalDate end = f.dispensedAt().plusDays(f.daysSupply());
            // clip
            if (start.isBefore(windowStart)) {
                start = windowStart;
            }
            if (end.isAfter(windowEnd)) {
                end = windowEnd;
            }
            if (!start.isBefore(end)) {
                continue;
            }
            intervals.add(new long[] {start.toEpochDay(), end.toEpochDay()});
        }
        if (intervals.isEmpty()) {
            return 0;
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long covered = 0;
        long curStart = intervals.get(0)[0];
        long curEnd = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            long[] iv = intervals.get(i);
            if (iv[0] <= curEnd) {
                curEnd = Math.max(curEnd, iv[1]);
            } else {
                covered += curEnd - curStart;
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        covered += curEnd - curStart;
        return covered;
    }
}
