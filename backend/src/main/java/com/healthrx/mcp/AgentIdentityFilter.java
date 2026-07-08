package com.healthrx.mcp;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import com.healthrx.domain.AgentName;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Captures the calling agent's identity for MCP requests. A request carrying a valid
 * {@code X-Agent-Id} + {@code X-Agent-Key} pair binds that agent to the request thread;
 * anything else leaves the context empty — harmless for initialize/tools-list, but every
 * action tool refuses to execute without an identity (call-time authorization, design §2).
 */
public class AgentIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentIdentityFilter.class);

    public static final String AGENT_ID_HEADER = "X-Agent-Id";
    public static final String AGENT_KEY_HEADER = "X-Agent-Key";

    private final McpAgentProperties properties;

    public AgentIdentityFilter(McpAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String agentId = request.getHeader(AGENT_ID_HEADER);
        String agentKey = request.getHeader(AGENT_KEY_HEADER);
        try {
            if (agentId != null && agentKey != null) {
                AgentName.fromWire(agentId).ifPresent(agent -> {
                    String expected = properties.agentKeys().get(agent.wireName());
                    if (expected != null && constantTimeEquals(expected, agentKey)) {
                        AgentCallContext.set(agent);
                    } else {
                        log.warn("Rejected MCP agent identity: bad key for {}", agentId);
                    }
                });
            }
            chain.doFilter(request, response);
        } finally {
            AgentCallContext.clear();
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
