package com.healthrx.agent.financial.mcp;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Deterministic guard reads issued by agent code (not the model) through the Postgres MCP
 * server's {@code executeQuery} tool — so even the agent's bookkeeping reads are audited
 * gateway tool calls.
 */
@Component
public class McpSql {

    private final McpSyncClient postgres;
    private final ObjectMapper mapper;

    public McpSql(McpSyncClient postgresMcp, ObjectMapper mapper) {
        this.postgres = postgresMcp;
        this.mapper = mapper;
    }

    /** Runs a read-only SQL statement; returns rows as column-name -> value maps. */
    public List<Map<String, Object>> query(String sql) {
        McpSchema.CallToolResult result = postgres.callTool(
                new McpSchema.CallToolRequest("executeQuery", Map.of("query", sql)));
        String text = text(result);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP executeQuery failed: " + text);
        }
        try {
            return mapper.readValue(text, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("MCP executeQuery returned non-JSON: " + text, e);
        }
    }

    public Object scalar(String sql) {
        List<Map<String, Object>> rows = query(sql);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> first = rows.get(0);
        return first.isEmpty() ? null : first.values().iterator().next();
    }

    private static String text(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "";
        }
        var first = result.content().get(0);
        return first instanceof McpSchema.TextContent tc ? tc.text() : String.valueOf(first);
    }
}
