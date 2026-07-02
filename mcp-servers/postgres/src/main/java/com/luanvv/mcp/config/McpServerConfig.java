package com.luanvv.mcp.config;

import com.luanvv.mcp.service.PostgresService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider postgresTools(PostgresService postgresService) {
      return MethodToolCallbackProvider.builder()
          .toolObjects(postgresService)
          .build();
    }
}
