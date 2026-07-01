package com.shields.healthrx.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferralStatusTest {

    @Test
    void allowsCanonicalTransitions() {
        assertThat(ReferralStatus.ELIGIBILITY_IDENTIFIED.canTransitionTo(ReferralStatus.BENEFITS_INVESTIGATION)).isTrue();
        assertThat(ReferralStatus.BENEFITS_INVESTIGATION.canTransitionTo(ReferralStatus.READY_TO_FILL)).isTrue();
        assertThat(ReferralStatus.PRIOR_AUTH_DENIED.canTransitionTo(ReferralStatus.PRIOR_AUTH_SUBMITTED)).isTrue();
        assertThat(ReferralStatus.DELIVERY_SCHEDULED.canTransitionTo(ReferralStatus.ACTIVE_THERAPY)).isTrue();
    }

    @Test
    void rejectsIllegalTransitions() {
        assertThat(ReferralStatus.ELIGIBILITY_IDENTIFIED.canTransitionTo(ReferralStatus.ACTIVE_THERAPY)).isFalse();
        assertThat(ReferralStatus.BENEFITS_INVESTIGATION.canTransitionTo(ReferralStatus.ACTIVE_THERAPY)).isFalse();
        assertThat(ReferralStatus.READY_TO_FILL.canTransitionTo(ReferralStatus.PRIOR_AUTH_SUBMITTED)).isFalse();
        assertThat(ReferralStatus.ACTIVE_THERAPY.canTransitionTo(ReferralStatus.READY_TO_FILL)).isFalse();
    }

    @Test
    void everyNonCancelledStatusCanCancel() {
        for (ReferralStatus s : ReferralStatus.values()) {
            if (s != ReferralStatus.CANCELLED) {
                assertThat(s.canTransitionTo(ReferralStatus.CANCELLED))
                        .as("%s should allow CANCELLED", s).isTrue();
            }
        }
    }

    @Test
    void cancelledIsTerminal() {
        assertThat(ReferralStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(ReferralStatus.CANCELLED.allowedNextStatuses()).isEmpty();
    }

    @Test
    void mapsEachStatusToItsEnterEvent() {
        assertThat(ReferralStatus.ELIGIBILITY_IDENTIFIED.eventOnEnter()).isEqualTo(WorkflowEventType.REFERRAL_CREATED);
        assertThat(ReferralStatus.PRIOR_AUTH_APPROVED.eventOnEnter())
                .isEqualTo(WorkflowEventType.PRIOR_AUTHORIZATION_APPROVED);
        assertThat(ReferralStatus.ACTIVE_THERAPY.eventOnEnter()).isEqualTo(WorkflowEventType.THERAPY_ACTIVATED);
        assertThat(ReferralStatus.CANCELLED.eventOnEnter()).isEqualTo(WorkflowEventType.REFERRAL_CANCELLED);
    }

    @Test
    void parsesKnownAndRejectsUnknown() {
        assertThat(ReferralStatus.tryParse("READY_TO_FILL")).contains(ReferralStatus.READY_TO_FILL);
        assertThat(ReferralStatus.tryParse("NONSENSE")).isEmpty();
        assertThat(ReferralStatus.tryParse(null)).isEmpty();
    }
}
