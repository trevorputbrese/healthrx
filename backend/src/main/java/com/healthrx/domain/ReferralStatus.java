package com.healthrx.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Referral access-workflow status and the authoritative transition graph (see data-model.md).
 * Transition validation, milestone-timestamp writing, and event mapping are all driven from here.
 */
public enum ReferralStatus {
    ELIGIBILITY_IDENTIFIED("Eligibility identified"),
    BENEFITS_INVESTIGATION("Benefits investigation"),
    PRIOR_AUTH_SUBMITTED("Prior authorization submitted"),
    PRIOR_AUTH_APPROVED("Prior authorization approved"),
    PRIOR_AUTH_DENIED("Prior authorization denied"),
    FINANCIAL_ASSISTANCE_REVIEW("Financial assistance review"),
    READY_TO_FILL("Ready to fill"),
    DELIVERY_SCHEDULED("Delivery scheduled"),
    ACTIVE_THERAPY("Active therapy"),
    CANCELLED("Cancelled");

    private final String label;

    ReferralStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    private static final Map<ReferralStatus, Set<ReferralStatus>> TRANSITIONS =
            new EnumMap<>(ReferralStatus.class);

    static {
        TRANSITIONS.put(ELIGIBILITY_IDENTIFIED, orderedSet(BENEFITS_INVESTIGATION, CANCELLED));
        TRANSITIONS.put(BENEFITS_INVESTIGATION,
                orderedSet(PRIOR_AUTH_SUBMITTED, FINANCIAL_ASSISTANCE_REVIEW, READY_TO_FILL, CANCELLED));
        TRANSITIONS.put(PRIOR_AUTH_SUBMITTED, orderedSet(PRIOR_AUTH_APPROVED, PRIOR_AUTH_DENIED, CANCELLED));
        TRANSITIONS.put(PRIOR_AUTH_APPROVED, orderedSet(FINANCIAL_ASSISTANCE_REVIEW, READY_TO_FILL, CANCELLED));
        TRANSITIONS.put(PRIOR_AUTH_DENIED, orderedSet(PRIOR_AUTH_SUBMITTED, CANCELLED));
        TRANSITIONS.put(FINANCIAL_ASSISTANCE_REVIEW, orderedSet(READY_TO_FILL, CANCELLED));
        TRANSITIONS.put(READY_TO_FILL, orderedSet(DELIVERY_SCHEDULED, CANCELLED));
        TRANSITIONS.put(DELIVERY_SCHEDULED, orderedSet(ACTIVE_THERAPY, CANCELLED));
        TRANSITIONS.put(ACTIVE_THERAPY, orderedSet(CANCELLED));
        TRANSITIONS.put(CANCELLED, orderedSet());
    }

    private static Set<ReferralStatus> orderedSet(ReferralStatus... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    /** Statuses reachable from this status, in display order. */
    public Set<ReferralStatus> allowedNextStatuses() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(ReferralStatus target) {
        return target != null && allowedNextStatuses().contains(target);
    }

    public boolean isTerminal() {
        return allowedNextStatuses().isEmpty();
    }

    /**
     * The workflow event recorded when a referral transitions <em>into</em> this status.
     * {@code ELIGIBILITY_IDENTIFIED} maps to {@code ReferralCreated} (the initial history row).
     */
    public WorkflowEventType eventOnEnter() {
        return switch (this) {
            case ELIGIBILITY_IDENTIFIED -> WorkflowEventType.REFERRAL_CREATED;
            case BENEFITS_INVESTIGATION -> WorkflowEventType.BENEFITS_INVESTIGATION_STARTED;
            case PRIOR_AUTH_SUBMITTED -> WorkflowEventType.PRIOR_AUTHORIZATION_SUBMITTED;
            case PRIOR_AUTH_APPROVED -> WorkflowEventType.PRIOR_AUTHORIZATION_APPROVED;
            case PRIOR_AUTH_DENIED -> WorkflowEventType.PRIOR_AUTHORIZATION_DENIED;
            case FINANCIAL_ASSISTANCE_REVIEW -> WorkflowEventType.FINANCIAL_ASSISTANCE_FOUND;
            case READY_TO_FILL -> WorkflowEventType.READY_TO_FILL;
            case DELIVERY_SCHEDULED -> WorkflowEventType.DELIVERY_SCHEDULED;
            case ACTIVE_THERAPY -> WorkflowEventType.THERAPY_ACTIVATED;
            case CANCELLED -> WorkflowEventType.REFERRAL_CANCELLED;
        };
    }

    public static Optional<ReferralStatus> tryParse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ReferralStatus.valueOf(raw.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
