package com.healthrx.web.request;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Mutation request bodies. Required fields use bean validation (missing -> 422). */
public final class Requests {

    private Requests() {
    }

    public record StatusTransition(
            @NotBlank String toStatus,
            @NotNull UUID changedById,
            String note) {
    }

    public record Note(
            @NotNull UUID authorId,
            @NotBlank String body) {
    }

    public record Financials(
            @NotNull UUID changedById,
            BigDecimal copayAmount,
            BigDecimal financialAssistanceSecuredAmount,
            Boolean financialAssistanceRequired,
            String note) {
    }

    public record Outreach(
            UUID referralId,
            @NotNull UUID ownerId,
            @NotBlank String channel,
            @NotBlank String outcome,
            Instant occurredAt,
            String notes) {
    }

    public record Intervention(
            UUID referralId,
            @NotNull UUID ownerId,
            @NotBlank String interventionType,
            @NotBlank String summary,
            Instant occurredAt) {
    }
}
