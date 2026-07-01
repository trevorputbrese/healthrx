package com.shields.healthrx.web.dto;

import java.time.Instant;
import java.util.UUID;

/** Shared reference and item shapes used across multiple API responses. */
public final class CommonDtos {

    private CommonDtos() {
    }

    /** Reference with a human name (clinics, medications, payers). */
    public record EntityRef(UUID id, String name) {
    }

    /** Reference with a display name (care team members / actors / owners). */
    public record NamedRef(UUID id, String displayName) {
    }

    /** Patient reference as shown in queue rows. */
    public record PatientRef(UUID id, String displayName, String diseaseState) {
    }

    /** Compact open-task row used in referral and patient detail views. */
    public record TaskSummary(UUID id, String type, String status, String priority, String title, Instant dueAt) {
    }

    /** Note as shown in a referral's recent notes. */
    public record NoteItem(UUID id, NamedRef author, String body, Instant createdAt) {
    }
}
