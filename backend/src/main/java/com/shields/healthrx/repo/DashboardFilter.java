package com.shields.healthrx.repo;

import java.time.LocalDate;
import java.util.UUID;

/** Dashboard filter + window. Window bounds are date-only ([from, to) with to exclusive). */
public record DashboardFilter(
        LocalDate from,
        LocalDate to,
        UUID clinicId,
        String diseaseState,
        UUID payerId,
        UUID medicationId,
        UUID ownerId) {
}
