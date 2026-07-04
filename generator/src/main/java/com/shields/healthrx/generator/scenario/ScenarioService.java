package com.shields.healthrx.generator.scenario;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.shields.healthrx.generator.messaging.EventPublisher;
import com.shields.healthrx.generator.messaging.EventTypes;
import com.shields.healthrx.generator.sim.SimulationClock;
import com.shields.healthrx.generator.world.WorldReader;
import com.shields.healthrx.generator.world.WorldReader.TherapyRef;

/**
 * Presenter-triggered scenarios — deterministic event bursts for a controlled demo. They work
 * whether or not the ambient loop is running, stamped at the current simulated instant.
 */
@Service
public class ScenarioService {

    /** The seeded MS patient (Marlowe Okafor) used for the at-risk / resolve demo pair. */
    private static final String DEMO_MS_MRN = "PX-2044";

    private final SimulationClock clock;
    private final WorldReader world;
    private final EventPublisher publisher;

    public ScenarioService(SimulationClock clock, WorldReader world, EventPublisher publisher) {
        this.clock = clock;
        this.world = world;
        this.publisher = publisher;
    }

    public Map<String, Object> run(String name) {
        Instant now = clock.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", name);
        result.put("simInstant", now.toString());
        switch (name) {
            case "new-referral" -> result.put("emitted", newReferral(now));
            case "advance-referral" -> result.put("emitted", advanceReferral(now));
            case "submit-prior-auth" -> result.put("emitted", submitPriorAuth(now));
            case "send-at-risk" -> result.put("emitted", sendAtRisk(now));
            case "resolve-risk" -> result.put("emitted", resolveRisk(now));
            default -> throw new IllegalArgumentException("Unknown scenario: " + name);
        }
        return result;
    }

    /**
     * The Access Workflow Agent's payer beat: push the oldest pre-PA referral into
     * PRIOR_AUTH_SUBMITTED so the agent senses it and chases the payer for a decision. Chains
     * the benefits step first when the referral hasn't started benefits investigation yet.
     */
    private int submitPriorAuth(Instant now) {
        return world.pickPaSubmittableReferral().map(ref -> {
            int emitted = 0;
            Map<String, Object> p = new HashMap<>();
            p.put("referralId", ref.id().toString());
            p.put("patientId", ref.patientId().toString());
            if ("ELIGIBILITY_IDENTIFIED".equals(ref.currentStatus())) {
                publisher.publish(EventTypes.BENEFITS_INVESTIGATION_STARTED, now, new HashMap<>(p));
                emitted++;
            }
            publisher.publish(EventTypes.PRIOR_AUTHORIZATION_SUBMITTED, now, p);
            return emitted + 1;
        }).orElse(0);
    }

    private int newReferral(Instant now) {
        Optional<WorldReader.NewReferralSeed> seed = world.pickNewReferralSeed();
        if (seed.isEmpty()) {
            return 0;
        }
        WorldReader.NewReferralSeed s = seed.get();
        Map<String, Object> p = new HashMap<>();
        p.put("referralId", UUID.randomUUID().toString());
        p.put("patientId", s.patientId().toString());
        p.put("clinicId", s.clinicId().toString());
        p.put("medicationId", s.medicationId().toString());
        p.put("payerId", s.payerId().toString());
        p.put("ownerId", s.ownerId().toString());
        p.put("priority", "URGENT");
        p.put("paRequired", true);
        p.put("financialAssistanceRequired", true);
        publisher.publish(EventTypes.REFERRAL_CREATED, now, p);
        return 1;
    }

    private int advanceReferral(Instant now) {
        return world.pickAdvanceableReferral(now).map(ref -> {
            // Always push to the next clean step (benefits -> PA submitted etc.) for a predictable demo.
            String event = switch (ref.currentStatus()) {
                case "ELIGIBILITY_IDENTIFIED" -> EventTypes.BENEFITS_INVESTIGATION_STARTED;
                case "BENEFITS_INVESTIGATION" -> EventTypes.PRIOR_AUTHORIZATION_SUBMITTED;
                case "PRIOR_AUTH_SUBMITTED" -> EventTypes.PRIOR_AUTHORIZATION_APPROVED;
                case "PRIOR_AUTH_APPROVED" -> EventTypes.READY_TO_FILL;
                case "PRIOR_AUTH_DENIED" -> EventTypes.PRIOR_AUTHORIZATION_SUBMITTED;
                case "FINANCIAL_ASSISTANCE_REVIEW" -> EventTypes.READY_TO_FILL;
                case "READY_TO_FILL" -> EventTypes.DELIVERY_SCHEDULED;
                case "DELIVERY_SCHEDULED" -> EventTypes.THERAPY_ACTIVATED;
                default -> null;
            };
            if (event == null) {
                return 0;
            }
            Map<String, Object> p = new HashMap<>();
            p.put("referralId", ref.id().toString());
            publisher.publish(event, now, p);
            return 1;
        }).orElse(0);
    }

    private int sendAtRisk(Instant now) {
        Optional<TherapyRef> therapy = world.findActiveTherapyByMrn(DEMO_MS_MRN)
                .or(() -> world.pickRefillableTherapy(LocalDate.ofInstant(now, ZoneOffset.UTC).plusYears(10)));
        if (therapy.isEmpty()) {
            return 0;
        }
        TherapyRef t = therapy.get();
        LocalDate missedDate = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(2);
        // Overdue refill.
        Map<String, Object> miss = new HashMap<>();
        miss.put("fillId", UUID.randomUUID().toString());
        miss.put("therapyId", t.id().toString());
        miss.put("patientId", t.patientId().toString());
        miss.put("expectedRefillDate", missedDate.toString());
        miss.put("daysSupply", t.daysSupply());
        publisher.publish(EventTypes.REFILL_MISSED, now, miss);
        // Two unsuccessful outreach attempts -> trips the outreach risk condition too.
        publisher.publish(EventTypes.PATIENT_OUTREACH_LOGGED, now,
                outreach(t.patientId(), t.ownerId(), "PHONE", "NO_ANSWER", "No answer on refill reminder"));
        publisher.publish(EventTypes.PATIENT_OUTREACH_LOGGED, now,
                outreach(t.patientId(), t.ownerId(), "SMS", "LEFT_MESSAGE", "Left refill reminder"));
        return 3;
    }

    private int resolveRisk(Instant now) {
        Optional<TherapyRef> therapy = world.findActiveTherapyByMrn(DEMO_MS_MRN)
                .or(() -> world.pickRefillableTherapy(LocalDate.ofInstant(now, ZoneOffset.UTC).plusYears(10)));
        if (therapy.isEmpty()) {
            return 0;
        }
        TherapyRef t = therapy.get();
        LocalDate simDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        // A successful dispense clears the overdue condition; reached outreach + adherence
        // intervention clear the outreach condition.
        Map<String, Object> fill = new HashMap<>();
        fill.put("fillId", UUID.randomUUID().toString());
        fill.put("therapyId", t.id().toString());
        fill.put("patientId", t.patientId().toString());
        fill.put("daysSupply", t.daysSupply() > 0 ? t.daysSupply() : 30);
        fill.put("dispensedAt", simDate.toString());
        publisher.publish(EventTypes.PRESCRIPTION_FILLED, now, fill);
        publisher.publish(EventTypes.PATIENT_OUTREACH_LOGGED, now,
                outreach(t.patientId(), t.ownerId(), "PHONE", "REACHED", "Reached patient; refill confirmed"));
        Map<String, Object> intervention = new HashMap<>();
        intervention.put("patientId", t.patientId().toString());
        intervention.put("ownerId", t.ownerId().toString());
        intervention.put("interventionType", "ADHERENCE_COUNSELING");
        intervention.put("summary", "Reviewed refill schedule and adherence plan");
        publisher.publish(EventTypes.CLINICAL_INTERVENTION_CREATED, now, intervention);
        return 3;
    }

    private Map<String, Object> outreach(UUID patientId, UUID ownerId, String channel, String outcome, String notes) {
        Map<String, Object> p = new HashMap<>();
        p.put("patientId", patientId.toString());
        p.put("ownerId", ownerId.toString());
        p.put("channel", channel);
        p.put("outcome", outcome);
        p.put("notes", notes);
        return p;
    }
}
