package com.shields.healthrx.agent.adherence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent wiring: identity (name + shared secret sent as X-Agent-Id/X-Agent-Key through the
 * gateway), the MCP gateway base URL and per-server endpoints, and the guard tuning knobs.
 * See phase-3-design.md §4/§6.
 */
@ConfigurationProperties(prefix = "healthrx.agent")
public record AgentProperties(
        String name,
        String key,
        String gatewayUrl,
        String postgresEndpoint,
        String healthrxEndpoint,
        String knowledgeEndpoint,
        int cooldownSimHours,
        long waitProcessedTimeoutMs) {
}
