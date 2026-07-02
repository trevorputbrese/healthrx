package com.shields.healthrx.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HealthRx-embedded MCP server: publishes the action tools at /mcp (Spring AI MCP server
 * starter, streamable HTTP) and installs the per-agent identity filter. Registered behind the
 * MCP gateway via {@code cf bind-service healthrx healthrx-mcp-gw}. Design phase-3-design.md §5.2.
 */
@Configuration
@EnableConfigurationProperties(McpAgentProperties.class)
public class McpServerConfig {

    @Bean
    ToolCallbackProvider healthRxTools(HealthRxActionTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    FilterRegistrationBean<AgentIdentityFilter> agentIdentityFilter(McpAgentProperties properties) {
        FilterRegistrationBean<AgentIdentityFilter> registration =
                new FilterRegistrationBean<>(new AgentIdentityFilter(properties));
        registration.addUrlPatterns("/mcp", "/mcp/*");
        registration.setOrder(10);
        return registration;
    }
}
