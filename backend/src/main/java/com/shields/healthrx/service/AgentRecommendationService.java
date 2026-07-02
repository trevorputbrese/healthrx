package com.shields.healthrx.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.domain.AgentName;
import com.shields.healthrx.domain.RecommendationStatus;
import com.shields.healthrx.repo.AgentRecommendationRepository;
import com.shields.healthrx.web.ApiException;

/**
 * Consumer-side handling of the two agent events (design §6): {@code AgentRecommendationCreated}
 * inserts the recommendation row (and supersedes any stale open PENDING one for the same
 * agent+patient); {@code AgentRecommendationApplied} is a state repair (PENDING/APPLYING ->
 * APPLIED), healing timeout-revert and API-restart races.
 */
@Service
public class AgentRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(AgentRecommendationService.class);

    private final AgentRecommendationRepository recommendations;
    private final ObjectMapper mapper;

    public AgentRecommendationService(AgentRecommendationRepository recommendations, ObjectMapper mapper) {
        this.recommendations = recommendations;
        this.mapper = mapper;
    }

    @Transactional
    public void recordCreated(Map<String, Object> payload, Instant at) {
        UUID id = reqUuid(payload, "recommendationId");
        String agentName = reqStr(payload, "agentName");
        AgentName agent = AgentName.fromWire(agentName)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_AGENT",
                        "Unknown agent: " + agentName, Map.of("agentName", agentName)));
        UUID patientId = reqUuid(payload, "patientId");

        String status = payload.getOrDefault("status", RecommendationStatus.PENDING.name()).toString();
        if (!status.equals(RecommendationStatus.PENDING.name())
                && !status.equals(RecommendationStatus.AUTO_APPLIED.name())) {
            throw ApiException.unprocessable("INVALID_RECOMMENDATION_STATUS",
                    "A recommendation is born PENDING or AUTO_APPLIED, not " + status,
                    Map.of("status", status));
        }

        boolean inserted = recommendations.insert(
                id, agent.wireName(), patientId,
                uuid(payload, "referralId"), uuid(payload, "therapyId"), uuid(payload, "taskId"),
                uuid(payload, "triggerEventId"), str(payload, "triggerEventType"),
                status, reqStr(payload, "summary"),
                json(payload.get("recommendation"), "{}"), json(payload.get("trace"), "[]"), at);
        if (!inserted) {
            return; // duplicate recommendation id: idempotent no-op (belt to processed_events' braces)
        }
        int superseded = recommendations.supersedeOpenPending(agent.wireName(), patientId, id, at);
        if (superseded > 0) {
            log.info("agent_recommendation superseded {} stale PENDING row(s) agent={} patient={}",
                    superseded, agent.wireName(), patientId);
        }
    }

    @Transactional
    public void recordApplied(Map<String, Object> payload, Instant at) {
        UUID id = reqUuid(payload, "recommendationId");
        int repaired = recommendations.applyRepair(id, at);
        if (repaired == 0) {
            log.debug("agent_recommendation applied event was a no-op (already terminal) id={}", id);
        }
    }

    private String json(Object value, String def) {
        if (value == null) {
            return def;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw ApiException.unprocessable("INVALID_RECOMMENDATION_JSON",
                    "Recommendation payload is not serializable JSON.", Map.of());
        }
    }

    private static UUID uuid(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : UUID.fromString(v.toString());
    }

    private static UUID reqUuid(Map<String, Object> p, String key) {
        UUID v = uuid(p, key);
        if (v == null) {
            throw ApiException.unprocessable("MISSING_FIELD", "Missing required event field: " + key,
                    Map.of("field", key));
        }
        return v;
    }

    private static String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? null : v.toString();
    }

    private static String reqStr(Map<String, Object> p, String key) {
        String v = str(p, key);
        if (v == null || v.isBlank()) {
            throw ApiException.unprocessable("MISSING_FIELD", "Missing required event field: " + key,
                    Map.of("field", key));
        }
        return v;
    }
}
