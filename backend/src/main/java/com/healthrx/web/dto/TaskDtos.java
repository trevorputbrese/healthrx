package com.healthrx.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.healthrx.web.dto.CommonDtos.NamedRef;

/** Tasks-page response shapes. */
public final class TaskDtos {

    private TaskDtos() {
    }

    public record Item(
            UUID id,
            String type,
            String status,
            String priority,
            String title,
            String description,
            Instant dueAt,
            Instant completedAt,
            Instant createdAt,
            NamedRef patient,
            UUID referralId,
            String referralNumber,
            NamedRef owner) {
    }
}
