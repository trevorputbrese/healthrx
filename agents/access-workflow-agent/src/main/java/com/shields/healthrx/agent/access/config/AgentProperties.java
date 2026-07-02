package com.shields.healthrx.agent.access.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent wiring: identity (name + shared secret through the gateway), MCP gateway base URL and
 * per-server endpoints, and the autonomy tuning knobs (stuck-scan thresholds, per-scan cap, and
 * the real-time LLM rate cap). See phase-3-design.md §4/§6.
 */
@ConfigurationProperties(prefix = "healthrx.agent")
public record AgentProperties(
        String name,
        String key,
        String gatewayUrl,
        String postgresEndpoint,
        String healthrxEndpoint,
        String knowledgeEndpoint,
        long waitProcessedTimeoutMs,
        int scanIntervalMs,
        int paStuckDays,
        int statusStuckDays,
        int scanCap,
        int ratePerMinute) {
}
