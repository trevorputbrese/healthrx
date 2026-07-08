package com.healthrx.agent.adherence.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.healthrx.agent.adherence.config.AgentProperties;
import com.healthrx.agent.adherence.core.RecommendationModels.Recommendation;
import com.healthrx.agent.adherence.llm.RecommendationEngine;
import com.healthrx.agent.adherence.messaging.EventEnvelope;
import com.healthrx.agent.adherence.messaging.EventPublisher;

/**
 * The run loop (phase-3-design.md §6): sense RefillMissed -> guards -> wait for the trigger to be
 * applied -> investigate + reason via the LLM and gateway tools -> emit AgentRecommendationCreated
 * (PENDING). Recommendation ids are deterministic per trigger so redeliveries dedupe downstream.
 */
@Service
public class AdherenceAgentService {

    private static final Logger log = LoggerFactory.getLogger(AdherenceAgentService.class);

    private final Guards guards;
    private final RecommendationEngine engine;
    private final EventPublisher publisher;
    private final AgentProperties props;
    /** Covers the async window before the consumer writes the recommendation row (§2 guardrail 2). */
    private final Set<UUID> inFlightPatients = ConcurrentHashMap.newKeySet();

    public AdherenceAgentService(Guards guards, RecommendationEngine engine, EventPublisher publisher,
            AgentProperties props) {
        this.guards = guards;
        this.engine = engine;
        this.publisher = publisher;
        this.props = props;
    }

    public void onRefillMissed(EventEnvelope env) {
        Map<String, Object> p = env.payload() == null ? Map.of() : env.payload();
        UUID patientId = uuid(p, "patientId");
        UUID therapyId = uuid(p, "therapyId");
        UUID referralId = uuid(p, "referralId");
        if (patientId == null || therapyId == null) {
            log.warn("RefillMissed without patientId/therapyId — skipping. eventId={}", env.eventId());
            return;
        }

        if (!inFlightPatients.add(patientId)) {
            log.info("Run already in flight for patient {} — skipping trigger {}", patientId, env.eventId());
            return;
        }
        try {
            run(env, patientId, therapyId, referralId);
        } finally {
            inFlightPatients.remove(patientId);
        }
    }

    private void run(EventEnvelope env, UUID patientId, UUID therapyId, UUID referralId) {
        Guards.Decision decision = guards.evaluate(patientId);
        if (!decision.proceed()) {
            log.info("Guard skip for patient {}: {}", patientId, decision.reason());
            return;
        }

        TraceRecorder trace = new TraceRecorder();
        trace.step("trigger", "RefillMissed for patient " + patientId + " (event " + env.eventId() + ")");
        if (decision.reason() != null && !decision.reason().isBlank()) {
            trace.step("guards", decision.reason());
        }

        if (!guards.waitForProcessed(env.eventId())) {
            log.warn("Trigger {} not visible in processed_events within timeout — proceeding with"
                    + " possibly stale context", env.eventId());
            trace.step("guards", "warning: trigger not yet applied by the API consumer");
        }

        Recommendation rec = engine.recommend(patientId, therapyId, referralId, trace);
        trace.step("proposal", rec.summary() != null ? rec.summary() : "recommendation drafted");

        UUID recommendationId = AgentIds.recommendationId(props.name(), env.eventId());
        Instant occurredAt = guards.simNow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendationId);
        payload.put("agentName", props.name());
        payload.put("patientId", patientId);
        payload.put("referralId", referralId);
        payload.put("therapyId", therapyId);
        payload.put("triggerEventId", env.eventId());
        payload.put("triggerEventType", env.eventType());
        payload.put("status", "PENDING");
        payload.put("summary", rec.summary() != null ? rec.summary()
                : "Adherence risk plan: outreach, counseling, and refill");
        payload.put("recommendation", Map.of(
                "riskExplanation", nullSafe(rec.riskExplanation()),
                "outreach", Map.of(
                        "channel", rec.outreach() != null && rec.outreach().channel() != null
                                ? rec.outreach().channel() : "PHONE",
                        "script", rec.outreach() != null ? nullSafe(rec.outreach().script()) : ""),
                "intervention", Map.of(
                        "type", rec.intervention() != null && rec.intervention().type() != null
                                ? rec.intervention().type() : "ADHERENCE_COUNSELING",
                        "rationale", rec.intervention() != null ? nullSafe(rec.intervention().rationale()) : ""),
                "refillPlan", Map.of(
                        "daysSupply", rec.refillPlan() != null && rec.refillPlan().daysSupply() != null
                                ? rec.refillPlan().daysSupply() : 30,
                        "note", rec.refillPlan() != null ? nullSafe(rec.refillPlan().note()) : "")));
        payload.put("trace", trace.steps());

        publisher.publish(AgentIds.createdEventId(recommendationId), "AgentRecommendationCreated",
                occurredAt, payload);
        log.info("Recommendation {} created (PENDING) for patient {}", recommendationId, patientId);
    }

    private static String nullSafe(String v) {
        return v == null ? "" : v;
    }

    private static UUID uuid(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : UUID.fromString(v.toString());
    }
}
