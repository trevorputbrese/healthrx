package com.shields.healthrx.generator.flow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.shields.healthrx.generator.messaging.EventPublisher;
import com.shields.healthrx.generator.messaging.EventTypes;
import com.shields.healthrx.generator.sim.SimulationClock;
import com.shields.healthrx.generator.sim.SimulationStateRepository.State;
import com.shields.healthrx.generator.world.WorldReader;

/**
 * The ambient trickle: when the simulation is enabled, each tick advances the simulated clock and
 * emits one realistic event (advance a referral, fill/miss a refill, create a referral, or log an
 * engagement). Rates are deliberately modest so the demo stays legible.
 */
@Component
public class AmbientGenerator {

    private static final String[] PRIORITIES = {"LOW", "MEDIUM", "HIGH", "URGENT"};
    private static final String[] CHANNELS = {"PHONE", "SMS", "EMAIL", "PORTAL"};
    private static final String[] OUTCOMES = {"REACHED", "LEFT_MESSAGE", "NO_ANSWER", "NEEDS_FOLLOW_UP", "DECLINED"};
    private static final String[] INTERVENTIONS =
            {"ADHERENCE_COUNSELING", "SIDE_EFFECT_MANAGEMENT", "DOSE_CLARIFICATION", "LAB_MONITORING", "CARE_COORDINATION"};

    private final SimulationClock clock;
    private final WorldReader world;
    private final EventPublisher publisher;
    private final AccessFlow flow;
    private final Random rnd = new Random();

    public AmbientGenerator(SimulationClock clock, WorldReader world, EventPublisher publisher, AccessFlow flow) {
        this.clock = clock;
        this.world = world;
        this.publisher = publisher;
        this.flow = flow;
    }

    @Scheduled(fixedDelayString = "${healthrx.generator.tick-interval-ms}")
    public void tick() {
        State state = clock.state();
        if (!state.enabled()) {
            return; // paused: no time passes, no events
        }
        Instant simNow = clock.advanceTick();
        if (!state.ambientEnabled()) {
            return; // time passes, but only presenter scenario clicks generate events
        }
        double roll = rnd.nextDouble();
        if (roll < 0.40) {
            advanceReferral(simNow);
        } else if (roll < 0.70) {
            refillAction(simNow);
        } else if (roll < 0.85) {
            createReferral(simNow);
        } else {
            engage(simNow);
        }
    }

    private void advanceReferral(Instant simNow) {
        world.pickAdvanceableReferral().ifPresent(ref -> {
            String event = flow.nextEvent(ref.currentStatus());
            if (event == null) {
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("referralId", ref.id().toString());
            publisher.publish(event, simNow, payload);
        });
    }

    private void refillAction(Instant simNow) {
        LocalDate simDate = LocalDate.ofInstant(simNow, ZoneOffset.UTC);
        world.pickRefillableTherapy(simDate).ifPresent(t -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("therapyId", t.id().toString());
            payload.put("patientId", t.patientId().toString());
            payload.put("daysSupply", t.daysSupply());
            if (rnd.nextDouble() < 0.85) {
                payload.put("fillId", UUID.randomUUID().toString());
                payload.put("dispensedAt", simDate.toString());
                publisher.publish(EventTypes.PRESCRIPTION_FILLED, simNow, payload);
            } else {
                payload.put("fillId", UUID.randomUUID().toString());
                payload.put("expectedRefillDate", t.currentRefillDueDate().toString());
                publisher.publish(EventTypes.REFILL_MISSED, simNow, payload);
            }
        });
    }

    private void createReferral(Instant simNow) {
        world.pickNewReferralSeed().ifPresent(seed -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("referralId", UUID.randomUUID().toString());
            payload.put("patientId", seed.patientId().toString());
            payload.put("clinicId", seed.clinicId().toString());
            payload.put("medicationId", seed.medicationId().toString());
            payload.put("payerId", seed.payerId().toString());
            payload.put("ownerId", seed.ownerId().toString());
            payload.put("priority", PRIORITIES[rnd.nextInt(PRIORITIES.length)]);
            payload.put("paRequired", rnd.nextDouble() < 0.7);
            payload.put("financialAssistanceRequired", rnd.nextDouble() < 0.4);
            publisher.publish(EventTypes.REFERRAL_CREATED, simNow, payload);
        });
    }

    private void engage(Instant simNow) {
        world.pickEngagementTarget().ifPresent(target -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("patientId", target.patientId().toString());
            if (target.referralId() != null) {
                payload.put("referralId", target.referralId().toString());
            }
            payload.put("ownerId", target.ownerId().toString());
            if (rnd.nextBoolean()) {
                payload.put("channel", CHANNELS[rnd.nextInt(CHANNELS.length)]);
                payload.put("outcome", OUTCOMES[rnd.nextInt(OUTCOMES.length)]);
                payload.put("notes", "Ambient outreach");
                publisher.publish(EventTypes.PATIENT_OUTREACH_LOGGED, simNow, payload);
            } else {
                payload.put("interventionType", INTERVENTIONS[rnd.nextInt(INTERVENTIONS.length)]);
                payload.put("summary", "Ambient clinical intervention");
                publisher.publish(EventTypes.CLINICAL_INTERVENTION_CREATED, simNow, payload);
            }
        });
    }
}
