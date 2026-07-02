package com.luanvv.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@SpringBootApplication
public class McpServerPostgresApplication {

    public static void main(String[] args) {
        log.info("Starting MCP Server Postgres Application...");
        try {
            SpringApplication.run(McpServerPostgresApplication.class, args);
            log.info("MCP Server Postgres Application started successfully");
        } catch (Exception e) {
            log.error("Failed to start MCP Server Postgres Application", e);
            System.exit(1);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("MCP Server Postgres Application is ready to accept requests");
    }
}
