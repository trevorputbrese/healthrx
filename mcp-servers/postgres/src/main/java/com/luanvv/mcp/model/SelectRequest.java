package com.luanvv.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.ToolParam;

public record SelectRequest(
    @ToolParam(description = "Name of the table to select from")
    String table,
    @ToolParam(description = "Schema of the table, default is 'public'", required = false)
    String schema,
    String conditions,
    String orderBy,
    Integer limit
) {
    public SelectRequest {
        if (schema == null) {
            schema = "public"; // Default schema if not provided
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }
        if (limit != null && limit <= 0) {
            throw new IllegalArgumentException("Limit must be a positive integer");
        }
    }
}