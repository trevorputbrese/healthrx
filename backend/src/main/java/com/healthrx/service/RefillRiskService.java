package com.healthrx.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.healthrx.config.AppTime;
import com.healthrx.metric.MetricCalculations.FillCoverage;
import com.healthrx.metric.RefillRiskCalculator;
import com.healthrx.metric.RefillRiskCalculator.Inputs;
import com.healthrx.metric.RefillRiskCalculator.Result;
import com.healthrx.repo.RiskDataRepository;
import com.healthrx.repo.RiskDataRepository.ActiveTherapyRow;
import com.healthrx.repo.RiskDataRepository.DispensedFillRow;

/** Computes refill risk for sets of therapies by composing batch-loaded inputs. */
@Service
public class RefillRiskService {

    private final RiskDataRepository riskData;
    private final AppTime time;

    public RefillRiskService(RiskDataRepository riskData, AppTime time) {
        this.riskData = riskData;
        this.time = time;
    }

    /** A therapy is risk-eligible when it is ACTIVE and started on or before today. */
    public boolean isActiveForRisk(ActiveTherapyRow t, LocalDate today) {
        return "ACTIVE".equals(t.status()) && (t.startDate() == null || !t.startDate().isAfter(today));
    }

    public Map<UUID, Result> computeForActive() {
        return compute(riskData.activeTherapies());
    }

    public Map<UUID, Result> computeForTherapyIds(Collection<UUID> therapyIds) {
        return compute(riskData.therapiesByIds(therapyIds));
    }

    /** Computes risk for the active subset of the supplied therapies, keyed by therapy id. */
    public Map<UUID, Result> compute(Collection<ActiveTherapyRow> therapies) {
        Instant now = time.now();
        LocalDate today = time.today();
        List<ActiveTherapyRow> active = therapies.stream().filter(t -> isActiveForRisk(t, today)).toList();
        if (active.isEmpty()) {
            return Map.of();
        }
        List<UUID> therapyIds = active.stream().map(ActiveTherapyRow::therapyId).toList();
        List<UUID> patientIds = active.stream().map(ActiveTherapyRow::patientId).distinct().toList();
        Instant cutoff = now.minus(14, ChronoUnit.DAYS);

        Map<UUID, List<DispensedFillRow>> fills = riskData.dispensedFillsByTherapy(therapyIds);
        var outreach = riskData.recentOutreachByPatient(patientIds, cutoff);
        var interventions = riskData.recentInterventionsByPatient(patientIds, cutoff);
        var tasks = riskData.openContactTasksByPatient(patientIds);

        Map<UUID, Result> out = new LinkedHashMap<>();
        for (ActiveTherapyRow t : active) {
            List<DispensedFillRow> dispensed = fills.getOrDefault(t.therapyId(), List.of());
            // The therapy's current_refill_due_date is the canonical next-refill date, kept in sync
            // by fills (a dispense rolls it forward; a recorded miss leaves it in the past). Fall back
            // to the latest dispensed fill's expected date only when the column is unset.
            LocalDate currentDue = t.currentRefillDueDate() != null
                    ? t.currentRefillDueDate()
                    : dispensed.stream().map(DispensedFillRow::expectedRefillDate).filter(Objects::nonNull)
                            .max(Comparator.naturalOrder()).orElse(null);
            List<FillCoverage> coverages = dispensed.stream()
                    .map(d -> new FillCoverage(d.dispensedAt(), d.daysSupply()))
                    .toList();
            Inputs inputs = new Inputs(
                    t.startDate(), currentDue, coverages,
                    outreach.getOrDefault(t.patientId(), List.of()),
                    interventions.getOrDefault(t.patientId(), List.of()),
                    tasks.getOrDefault(t.patientId(), List.of()));
            out.put(t.therapyId(), RefillRiskCalculator.compute(now, today, inputs));
        }
        return out;
    }
}
