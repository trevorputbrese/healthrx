package com.healthrx.mcp;

import java.util.Optional;

import com.healthrx.domain.AgentName;

/**
 * Per-request agent identity for the embedded MCP server. The {@link AgentIdentityFilter}
 * populates it from the {@code X-Agent-Id}/{@code X-Agent-Key} headers (forwarded through the
 * MCP gateway) and clears it after the request; the action tools read it at call time to
 * authorize each invocation (phase-3-design.md §2 guardrail 1). With the SYNC WebMVC MCP
 * server, tool execution runs on the request thread, so a ThreadLocal carries the identity —
 * verified by McpToolsIT; the design's fallback is per-agent bearer tokens if a future
 * transport breaks this assumption.
 */
public final class AgentCallContext {

    private static final ThreadLocal<AgentName> CURRENT = new ThreadLocal<>();

    private AgentCallContext() {
    }

    static void set(AgentName agent) {
        CURRENT.set(agent);
    }

    static void clear() {
        CURRENT.remove();
    }

    public static Optional<AgentName> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
