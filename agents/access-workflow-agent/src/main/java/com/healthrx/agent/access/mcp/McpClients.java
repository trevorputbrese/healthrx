package com.healthrx.agent.access.mcp;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.healthrx.agent.access.config.AgentProperties;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;

/**
 * MCP clients to the gateway (phase-3-design.md §3): every read (Postgres MCP server) and every
 * write (HealthRx-embedded action tools) is an audited tool call through healthrx-mcp-gw. Both
 * clients carry the agent's interim identity headers (X-Agent-Id/X-Agent-Key, forwarded upstream
 * by the gateway) and a distinctive User-Agent for the mcp.audit logs.
 */
@Configuration
public class McpClients {

    public static final String USER_AGENT = "healthrx-access-workflow-agent";

    @Bean(destroyMethod = "close")
    public McpSyncClient postgresMcp(AgentProperties props) {
        return client(props, props.postgresEndpoint());
    }

    @Bean(destroyMethod = "close")
    public McpSyncClient healthrxMcp(AgentProperties props) {
        return client(props, props.healthrxEndpoint());
    }

    @Bean(destroyMethod = "close")
    public McpSyncClient knowledgeMcp(AgentProperties props) {
        return client(props, props.knowledgeEndpoint());
    }

    private static McpSyncClient client(AgentProperties props, String endpoint) {
        var transport = HttpClientStreamableHttpTransport.builder(props.gatewayUrl())
                .endpoint(endpoint)
                .customizeRequest(request -> request
                        .header("X-Agent-Id", props.name())
                        .header("X-Agent-Key", props.key())
                        .header("User-Agent", USER_AGENT))
                .build();
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
    }
}
