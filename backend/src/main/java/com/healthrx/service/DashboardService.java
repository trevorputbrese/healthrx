package com.healthrx.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.healthrx.config.AppTime;
import com.healthrx.domain.RefillRiskLevel;
import com.healthrx.metric.MetricCalculations;
import com.healthrx.metric.MetricCalculations.FillCoverage;
import com.healthrx.metric.RefillRiskCalculator;
import com.healthrx.repo.DashboardFilter;
import com.healthrx.repo.DashboardRepository;
import com.healthrx.repo.DashboardRepository.FinancialResult;
import com.healthrx.repo.RiskDataRepository;
import com.healthrx.repo.RiskDataRepository.ActiveTherapyRow;
import com.healthrx.repo.RiskDataRepository.DispensedFillRow;
import com.healthrx.web.dto.DashboardDtos;

/** Builds the outcomes dashboard summary tiles and trend series from current database state. */
@Service
public class DashboardService {

    private final DashboardRepository dashboard;
    private final RiskDataRepository riskData;
    private final RefillRiskService riskService;
    private final AppTime time;

    public DashboardService(DashboardRepository dashboard, RiskDataRepository riskData,
                            RefillRiskService riskService, AppTime time) {
        this.dashboard = dashboard;
        this.riskData = riskData;
        this.riskService = riskService;
        this.time = time;
    }

    @Transactional(readOnly = true)
    public DashboardDtos.Summary summary(DashboardFilter filter) {
        LocalDate today = time.today();
        Instant now = time.now();

        List<UUID> activeIds = dashboard.activeTherapyIds(filter, today);
        Map<UUID, RefillRiskCalculator.Result> risk = riskService.computeForTherapyIds(activeIds);

        long refillRiskCount = risk.values().stream()
                .filter(r -> r.level() == RefillRiskLevel.MEDIUM || r.level() == RefillRiskLevel.HIGH).count();
        long highRefillRiskCount = risk.values().stream()
                .filter(r -> r.level() == RefillRiskLevel.HIGH).count();
        Integer avgAdherence = MetricCalculations.averagePercent(
                risk.values().stream().map(RefillRiskCalculator.Result::pdcPercent)
                        .filter(Objects::nonNull).toList());

        FinancialResult fin = dashboard.financialAssistance(filter);

        var tiles = new DashboardDtos.Tiles(
                dashboard.activePatientsOnTherapy(filter, today),
                MetricCalculations.medianDays(dashboard.timeToTherapyDays(filter)),
                MetricCalculations.medianDays(dashboard.paTurnaroundDays(filter)),
                refillRiskCount, highRefillRiskCount, avgAdherence,
                fin.amount(), fin.count(),
                dashboard.overdueTaskCount(filter, now));

        return new DashboardDtos.Summary(
                new DashboardDtos.Window(filter.from(), filter.to()),
                tiles,
                dashboard.statusCounts(filter),
                dashboard.openTasksByOwner(filter));
    }

    @Transactional(readOnly = true)
    public DashboardDtos.Trends trends(DashboardFilter filter, String bucket) {
        String resolved = "week".equalsIgnoreCase(bucket) ? "week" : "month";
        List<LocalDate[]> ranges = buckets(filter.from(), filter.to(), resolved);

        // Load the current active cohort once for the as-of adherence / risk series.
        LocalDate today = time.today();
        List<UUID> activeIds = dashboard.activeTherapyIds(filter, today);
        List<ActiveTherapyRow> cohort = riskData.therapiesByIds(activeIds);
        Map<UUID, List<DispensedFillRow>> fills = riskData.dispensedFillsByTherapy(activeIds);

        List<DashboardDtos.TrendBucket> series = new ArrayList<>();
        for (LocalDate[] range : ranges) {
            LocalDate bFrom = range[0];
            LocalDate bTo = range[1];
            var fromTs = bFrom.atStartOfDay().atOffset(ZoneOffset.UTC);
            var toTs = bTo.atStartOfDay().atOffset(ZoneOffset.UTC);

            long received = dashboard.countReferralsReceived(fromTs, toTs, filter);
            long activated = dashboard.countActivatedTherapies(fromTs, toTs, filter);
            Double medianTtt = MetricCalculations.medianDays(dashboard.timeToTherapyDaysBetween(fromTs, toTs, filter));
            Double medianPa = MetricCalculations.medianDays(dashboard.paTurnaroundDaysBetween(fromTs, toTs, filter));

            AsOf asOf = adherenceAndRiskAsOf(cohort, fills, bTo);

            series.add(new DashboardDtos.TrendBucket(
                    bFrom, bTo, received, activated, medianTtt, medianPa, asOf.avgPdc(), asOf.riskCount()));
        }
        return new DashboardDtos.Trends(resolved, series);
    }

    private record AsOf(Integer avgPdc, long riskCount) {
    }

    /**
     * Adherence (average PDC) and refill-risk count for the current active cohort, evaluated as
     * of {@code asOfDate} using only dispensed fills (outreach/task history is not reconstructed
     * historically, so trend risk reflects adherence + refill-due conditions).
     */
    private AsOf adherenceAndRiskAsOf(List<ActiveTherapyRow> cohort,
            Map<UUID, List<DispensedFillRow>> fills, LocalDate asOfDate) {
        List<Integer> pdcs = new ArrayList<>();
        long riskCount = 0;
        for (ActiveTherapyRow t : cohort) {
            if (t.startDate() == null || t.startDate().isAfter(asOfDate)) {
                continue;
            }
            List<DispensedFillRow> tf = fills.getOrDefault(t.therapyId(), List.of());
            List<FillCoverage> coverages = tf.stream()
                    .filter(d -> d.dispensedAt() != null && d.dispensedAt().isBefore(asOfDate))
                    .map(d -> new FillCoverage(d.dispensedAt(), d.daysSupply()))
                    .toList();
            Integer pdc = MetricCalculations.pdcPercent(t.startDate(), coverages, asOfDate);
            if (pdc != null) {
                pdcs.add(pdc);
            }
            LocalDate currentDue = tf.stream()
                    .filter(d -> d.dispensedAt() != null && d.dispensedAt().isBefore(asOfDate))
                    .map(DispensedFillRow::expectedRefillDate).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            boolean risky = (pdc != null && pdc < 90)
                    || (currentDue != null && !currentDue.isAfter(asOfDate.plusDays(7)));
            if (risky) {
                riskCount++;
            }
        }
        return new AsOf(MetricCalculations.averagePercent(pdcs), riskCount);
    }

    private List<LocalDate[]> buckets(LocalDate from, LocalDate to, String bucket) {
        List<LocalDate[]> out = new ArrayList<>();
        if ("week".equals(bucket)) {
            LocalDate s = from;
            while (s.isBefore(to)) {
                LocalDate e = s.plusWeeks(1);
                out.add(new LocalDate[] {s, e.isAfter(to) ? to : e});
                s = e;
            }
        } else {
            LocalDate s = from.withDayOfMonth(1);
            while (s.isBefore(to)) {
                LocalDate e = s.plusMonths(1);
                out.add(new LocalDate[] {s, e});
                s = e;
            }
        }
        return out;
    }
}
