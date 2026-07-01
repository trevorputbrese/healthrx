package com.shields.healthrx.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.shields.healthrx.web.dto.CommonDtos.NamedRef;

/** Patient journey timeline shapes. See api-contracts.md (GET /patients/{id}/timeline). */
public final class TimelineDtos {

    private TimelineDtos() {
    }

    public record Item(
            UUID id,
            String type,
            Instant occurredAt,
            String title,
            String body,
            NamedRef actor,
            Map<String, Object> metadata) {
    }

    public record Response(List<Item> items) {
    }
}
