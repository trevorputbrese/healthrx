package com.healthrx.mcp;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-agent shared secrets guarding the embedded MCP action tools until SSO lands
 * (phase-3-design.md §4). Keyed by agent wire name.
 */
@ConfigurationProperties(prefix = "healthrx.mcp")
public record McpAgentProperties(Map<String, String> agentKeys) {

    public McpAgentProperties {
        agentKeys = agentKeys == null ? Map.of() : agentKeys;
    }
}
