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
            NamedRef owner,
            ReferralAdvance advancesReferralTo) {
    }

    /** The referral advance a task completion performs (or just performed). */
    public record ReferralAdvance(
            UUID referralId,
            String referralNumber,
            String toStatus,
            String toStatusLabel) {
    }

    /** PATCH /api/tasks/{id}/status response: the task plus any referral advance it drove. */
    public record StatusChangeResult(Item task, ReferralAdvance referralAdvance) {
    }
}
