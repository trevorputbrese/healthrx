package com.healthrx.domain;

/**
 * Lifecycle of an agent recommendation. PENDING/APPLYING/APPLIED/DISMISSED is the
 * human-in-the-loop path (Adherence); AUTO_APPLIED is the autonomous path (Access);
 * SUPERSEDED closes a stale PENDING row when a newer recommendation for the same
 * subject arrives. See phase-3-design.md §6.
 */
public enum RecommendationStatus {
    PENDING,
    APPLYING,
    APPLIED,
    AUTO_APPLIED,
    DISMISSED,
    SUPERSEDED
}
