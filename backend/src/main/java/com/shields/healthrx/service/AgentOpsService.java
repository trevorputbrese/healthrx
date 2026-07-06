package com.shields.healthrx.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shields.healthrx.config.AppTime;
import com.shields.healthrx.domain.AgentName;
import com.shields.healthrx.repo.AgentControlRepository;
import com.shields.healthrx.repo.AgentRecommendationRepository;
import com.shields.healthrx.repo.AgentRecommendationRepository.Row;
import com.shields.healthrx.repo.CareTeamRepository;
import com.shields.healthrx.web.ApiException;
import com.shields.healthrx.web.dto.AgentDtos;
import com.shields.healthrx.web.dto.CommonDtos.NamedRef;
import com.shields.healthrx.web.dto.PageResponse;

/**
 * The Agents view backend: recommendation feed (with the APPLYING lazy re-arm), the hardened
 * approve flow (atomic PENDING->APPLYING gate, proxy to the owning agent, synchronous APPLIED,
 * 502 + revert on failure), dismiss, and the durable pause/resume switch. Design §6/§8.
 */
@Service
public class AgentOpsService {

    private static final Logger log = LoggerFactory.getLogger(AgentOpsService.class);

    /** Rows stuck APPLYING longer than this (wall-clock) are lazily re-armed to PENDING. */
    static final Duration APPLYING_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PROXY_TIMEOUT = Duration.ofSeconds(30);

    private final AgentRecommendationRepository recommendations;
    private final AgentControlRepository control;
    private final CareTeamRepository careTeam;
    private final AppTime time;
    private final ObjectMapper mapper;
    private final Map<AgentName, RestClient> agentClients = new EnumMap<>(AgentName.class);

    public AgentOpsService(AgentRecommendationRepository recommendations, AgentControlRepository control,
            CareTeamRepository careTeam, AppTime time, ObjectMapper mapper,
            @Value("${healthrx.agents.adherence-risk.url}") String adherenceUrl,
            @Value("${healthrx.agents.access-workflow.url}") String accessUrl,
            @Value("${healthrx.agents.financial-assistance.url}") String financialAssistanceUrl) {
        this.recommendations = recommendations;
        this.control = control;
        this.careTeam = careTeam;
        this.time = time;
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(PROXY_TIMEOUT);
        agentClients.put(AgentName.ADHERENCE_RISK,
                RestClient.builder().baseUrl(adherenceUrl).requestFactory(factory).build());
        agentClients.put(AgentName.ACCESS_WORKFLOW,
                RestClient.builder().baseUrl(accessUrl).requestFactory(factory).build());
        agentClients.put(AgentName.FINANCIAL_ASSISTANCE,
                RestClient.builder().baseUrl(financialAssistanceUrl).requestFactory(factory).build());
    }

    public PageResponse<AgentDtos.Recommendation> feed(String status, String agent, int page, int size) {
        // Crash recovery (§6 step 6): the read path re-arms rows whose approve died mid-flight.
        int rearmed = recommendations.rearmTimedOutApplying(Instant.now().minus(APPLYING_TIMEOUT));
        if (rearmed > 0) {
            log.warn("Re-armed {} recommendation(s) stuck APPLYING past {}s", rearmed, APPLYING_TIMEOUT.toSeconds());
        }
        List<AgentDtos.Recommendation> items = recommendations.page(status, agent, page, size)
                .stream().map(this::toDto).toList();
        return PageResponse.of(items, page, size, recommendations.count(status, agent));
    }

    public AgentDtos.AgentsResponse agents() {
        List<AgentDtos.AgentStatus> out = new ArrayList<>();
        for (AgentName agent : AgentName.values()) {
            boolean paused = control.find(agent.wireName())
                    .map(AgentControlRepository.Control::paused).orElse(true);
            var stats = recommendations.stats(agent.wireName()).orElse(null);
            out.add(new AgentDtos.AgentStatus(
                    agent.wireName(), agent.displayName(), paused, isReachable(agent),
                    stats != null ? stats.lastActivityAt() : null,
                    stats != null ? stats.total() : 0,
                    stats != null ? stats.pending() : 0,
                    stats != null ? stats.applied() : 0,
                    stats != null ? stats.autoApplied() : 0));
        }
        return new AgentDtos.AgentsResponse(out);
    }

    public AgentDtos.Recommendation approve(UUID id, UUID decidedById) {
        careTeam.requireActiveActor(decidedById);
        Row row = recommendations.find(id).orElseThrow(() -> ApiException.notFound("Recommendation", id));
        AgentName agent = AgentName.fromWire(row.agentName())
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_AGENT",
                        "Recommendation belongs to unknown agent: " + row.agentName(), Map.of()));

        // Atomic gate: only a PENDING row can enter APPLYING (double-clicks and races -> 409).
        if (!recommendations.gateApplying(id, decidedById, Instant.now())) {
            String current = recommendations.statusOf(id).orElse("UNKNOWN");
            throw ApiException.conflict("RECOMMENDATION_NOT_PENDING",
                    "Recommendation is " + current + ", not PENDING.", Map.of("status", current));
        }

        try {
            agentClients.get(agent).post()
                    .uri("/agent/recommendations/{id}/apply", id)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Agent apply proxy failed for {} (agent={}): {}", id, agent.wireName(), e.toString());
            recommendations.revertApplying(id);
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "AGENT_UNAVAILABLE",
                    "The " + agent.displayName() + " is not reachable; the recommendation is still pending.",
                    Map.of("agent", agent.wireName()));
        }

        // Synchronous APPLIED: the UI never waits on the event round-trip (§6 step 4). The
        // AgentRecommendationApplied event remains the audit-stream record; its consumer handler
        // repairs the row if this update lost a race.
        recommendations.markApplied(id, time.now());
        return recommendations.find(id).map(this::toDto)
                .orElseThrow(() -> ApiException.notFound("Recommendation", id));
    }

    public AgentDtos.Recommendation dismiss(UUID id, UUID decidedById) {
        careTeam.requireActiveActor(decidedById);
        if (!recommendations.dismiss(id, decidedById, time.now())) {
            String current = recommendations.statusOf(id)
                    .orElseThrow(() -> ApiException.notFound("Recommendation", id));
            throw ApiException.conflict("RECOMMENDATION_NOT_PENDING",
                    "Recommendation is " + current + ", not PENDING.", Map.of("status", current));
        }
        return recommendations.find(id).map(this::toDto)
                .orElseThrow(() -> ApiException.notFound("Recommendation", id));
    }

    public void setPaused(String agentWireName, boolean paused) {
        AgentName agent = AgentName.fromWire(agentWireName)
                .orElseThrow(() -> ApiException.notFound("Agent", agentWireName));
        control.upsert(agent.wireName(), paused, time.now());
        // Best-effort nudge so the agent notices immediately rather than on its next trigger.
        try {
            agentClients.get(agent).post().uri("/agent/control/refresh").retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.debug("Agent control refresh nudge failed (agent still honors the DB row): {}", e.toString());
        }
    }

    private boolean isReachable(AgentName agent) {
        try {
            agentClients.get(agent).get().uri("/agent/status").retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private AgentDtos.Recommendation toDto(Row row) {
        String agentDisplay = AgentName.fromWire(row.agentName())
                .map(AgentName::displayName).orElse(row.agentName());
        return new AgentDtos.Recommendation(
                row.id(), row.agentName(), agentDisplay,
                new NamedRef(row.patientId(), row.patientName()),
                row.referralId(), row.therapyId(), row.taskId(), row.status(), row.summary(),
                readMap(row.recommendationJson()), readList(row.traceJson()),
                row.createdAt(), row.decidedAt(),
                row.decidedById() != null ? new NamedRef(row.decidedById(), row.decidedByName()) : null);
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null ? Map.of() : mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    private List<Map<String, Object>> readList(String json) {
        try {
            return json == null ? List.of() : mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
