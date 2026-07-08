package com.healthrx.agent.adherence.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthrx.agent.adherence.config.AgentProperties;
import com.healthrx.agent.adherence.mcp.McpSql;
import com.healthrx.agent.adherence.messaging.EventPublisher;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Executes an approved recommendation (option B, phase-3-design.md §6): the human clicked
 * Approve, the API gated the row APPLYING and proxied here; this service performs the clinical
 * writes through the HealthRx-embedded MCP action tools (audited, ledger-idempotent per
 * recommendationId) and emits AgentRecommendationApplied. Safe to retry end-to-end.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);

    private final McpSyncClient healthrx;
    private final McpSql sql;
    private final EventPublisher publisher;
    private final Guards guards;
    private final AgentProperties props;
    private final ObjectMapper mapper;

    public ApplyService(McpSyncClient healthrxMcp, McpSql sql, EventPublisher publisher, Guards guards,
            AgentProperties props, ObjectMapper mapper) {
        this.healthrx = healthrxMcp;
        this.sql = sql;
        this.publisher = publisher;
        this.guards = guards;
        this.props = props;
        this.mapper = mapper;
    }

    public void apply(UUID recommendationId) {
        List<Map<String, Object>> rows = sql.query("""
                select patient_id, referral_id, therapy_id, recommendation::text as rec
                from agent_recommendations where id = '%s'""".formatted(recommendationId));
        if (rows.isEmpty()) {
            throw new IllegalStateException("Recommendation not found: " + recommendationId);
        }
        Map<String, Object> row = rows.get(0);
        String patientId = String.valueOf(row.get("patient_id"));
        String referralId = row.get("referral_id") != null ? String.valueOf(row.get("referral_id")) : null;
        String therapyId = row.get("therapy_id") != null ? String.valueOf(row.get("therapy_id")) : null;

        JsonNode rec = parse(String.valueOf(row.get("rec")));
        String script = rec.path("outreach").path("script").asText("");
        String interventionType = rec.path("intervention").path("type").asText("ADHERENCE_COUNSELING");
        String rationale = rec.path("intervention").path("rationale").asText("");
        int daysSupply = rec.path("refillPlan").path("daysSupply").asInt(30);

        // Each tool is exactly-once per (recommendationId, tool) server-side, so a partial
        // failure here followed by an API-driven retry resumes where it left off.
        callTool("log_outreach", mapOf(
                "recommendationId", recommendationId.toString(),
                "patientId", patientId,
                "referralId", referralId,
                "channel", "PHONE",
                "outcome", "REACHED",
                "notes", script.isBlank() ? "Adherence outreach call (agent recommendation)" : script));
        callTool("create_intervention", mapOf(
                "recommendationId", recommendationId.toString(),
                "patientId", patientId,
                "referralId", referralId,
                "interventionType", interventionType,
                "summary", rationale.isBlank() ? "Adherence counseling (agent recommendation)" : rationale));
        if (therapyId != null) {
            callTool("record_prescription_fill", mapOf(
                    "recommendationId", recommendationId.toString(),
                    "therapyId", therapyId,
                    "daysSupply", daysSupply));
        }

        Instant occurredAt = guards.simNow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("recommendationId", recommendationId);
        payload.put("agentName", props.name());
        publisher.publish(AgentIds.appliedEventId(recommendationId), "AgentRecommendationApplied",
                occurredAt, payload);
        log.info("Recommendation {} applied (outreach + intervention + fill)", recommendationId);
    }

    private void callTool(String name, Map<String, Object> args) {
        McpSchema.CallToolResult result = healthrx.callTool(new McpSchema.CallToolRequest(name, args));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("Tool " + name + " failed: " + firstText(result));
        }
        log.info("mcp_tool ok tool={} result={}", name, firstText(result));
    }

    private static String firstText(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        var first = result.content().get(0);
        return first instanceof McpSchema.TextContent tc ? tc.text() : String.valueOf(first);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Recommendation JSON unreadable", e);
        }
    }

    /** Map.of without null-value NPEs (referralId may legitimately be null). */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }
}
