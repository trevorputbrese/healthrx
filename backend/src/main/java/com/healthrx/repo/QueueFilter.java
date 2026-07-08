package com.healthrx.repo;

import java.util.UUID;

/** Parsed, typed filter for the referral queue list. Null fields mean "no filter". */
public record QueueFilter(
        String status,
        UUID clinicId,
        String diseaseState,
        UUID payerId,
        UUID medicationId,
        UUID ownerId,
        String priority,
        String search,
        boolean includeCancelled,
        int page,
        int size,
        String sortField,
        String sortDirection) {
}
