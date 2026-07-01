package com.shields.healthrx.service;

import java.time.Instant;

import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.repo.ReferralRepository.State;

/**
 * Pure milestone-timestamp stamping for an accepted referral transition. See the normative
 * transition→timestamp writer table in data-model.md. Transition <em>validity</em> is decided
 * by {@link ReferralStatus#canTransitionTo}; this only computes the resulting persisted state.
 */
public final class ReferralTransitions {

    private ReferralTransitions() {
    }

    public static State apply(State old, ReferralStatus from, ReferralStatus to, Instant now) {
        Instant benefits = old.benefitsInvestigationStartedAt();
        Instant paSubmitted = old.paSubmittedAt();
        Instant paDecided = old.paDecidedAt();
        Instant readyToFill = old.readyToFillAt();
        Instant deliveryScheduled = old.deliveryScheduledAt();
        Instant activeTherapy = old.activeTherapyAt();
        Instant closedAt = old.closedAt();

        switch (to) {
            case BENEFITS_INVESTIGATION -> benefits = firstNonNull(benefits, now);
            case PRIOR_AUTH_SUBMITTED -> {
                paSubmitted = now; // submit time of the current cycle
                if (from == ReferralStatus.PRIOR_AUTH_DENIED) {
                    paDecided = null; // resubmission: clear prior decision
                }
            }
            case PRIOR_AUTH_APPROVED, PRIOR_AUTH_DENIED -> paDecided = now;
            case READY_TO_FILL -> readyToFill = firstNonNull(readyToFill, now);
            case DELIVERY_SCHEDULED -> deliveryScheduled = firstNonNull(deliveryScheduled, now);
            case ACTIVE_THERAPY -> activeTherapy = firstNonNull(activeTherapy, now);
            case CANCELLED -> closedAt = now;
            default -> {
                // ELIGIBILITY_IDENTIFIED is never a transition target.
            }
        }

        return new State(
                to.name(), old.therapyId(), benefits, paSubmitted, paDecided, readyToFill,
                deliveryScheduled, activeTherapy, closedAt, old.financialAssistanceRequired(),
                old.financialAssistanceSecuredAmount(), old.copayAmount());
    }

    private static Instant firstNonNull(Instant a, Instant b) {
        return a != null ? a : b;
    }
}
