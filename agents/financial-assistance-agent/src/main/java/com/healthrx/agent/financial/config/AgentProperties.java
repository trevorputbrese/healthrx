package com.healthrx.agent.financial.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent wiring: identity (name + shared secret through the gateway), MCP gateway base URL and
 * per-server endpoints, the real-time rate cap, and the external BridgeFund portal the agent
 * contacts for financial-assistance decisions.
 */
@ConfigurationProperties(prefix = "healthrx.agent")
public record AgentProperties(
        String name,
        String key,
        String gatewayUrl,
        String postgresEndpoint,
        String healthrxEndpoint,
        long waitProcessedTimeoutMs,
        int ratePerMinute,
        String assistancePortalUrl) {
}
