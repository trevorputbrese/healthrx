package com.shields.healthrx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.shields.healthrx.domain.ReferralStatus;
import com.shields.healthrx.repo.ReferralRepository.State;

class ReferralTransitionsTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-29T00:00:00Z");

    private State baseState(String status) {
        return new State(status, UUID.randomUUID(), null, null, null, null, null, null, null,
                false, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void stampsBenefitsInvestigationWriteOnce() {
        State s = baseState("ELIGIBILITY_IDENTIFIED");
        State out = ReferralTransitions.apply(s, ReferralStatus.ELIGIBILITY_IDENTIFIED,
                ReferralStatus.BENEFITS_INVESTIGATION, NOW);
        assertThat(out.benefitsInvestigationStartedAt()).isEqualTo(NOW);

        // Re-entering does not move a write-once milestone.
        State withExisting = new State("BENEFITS_INVESTIGATION", s.therapyId(), T0, null, null, null, null, null, null,
                false, BigDecimal.ZERO, BigDecimal.ZERO);
        State out2 = ReferralTransitions.apply(withExisting, ReferralStatus.PRIOR_AUTH_DENIED,
                ReferralStatus.BENEFITS_INVESTIGATION, NOW);
        assertThat(out2.benefitsInvestigationStartedAt()).isEqualTo(T0);
    }

    @Test
    void paResubmissionResetsSubmittedAndClearsDecided() {
        State denied = new State("PRIOR_AUTH_DENIED", UUID.randomUUID(), T0, T0, T0, null, null, null, null,
                false, BigDecimal.ZERO, BigDecimal.ZERO);
        State out = ReferralTransitions.apply(denied, ReferralStatus.PRIOR_AUTH_DENIED,
                ReferralStatus.PRIOR_AUTH_SUBMITTED, NOW);
        assertThat(out.paSubmittedAt()).isEqualTo(NOW);
        assertThat(out.paDecidedAt()).isNull();
    }

    @Test
    void decisionStampsDecidedAt() {
        State submitted = new State("PRIOR_AUTH_SUBMITTED", UUID.randomUUID(), T0, T0, null, null, null, null, null,
                false, BigDecimal.ZERO, BigDecimal.ZERO);
        State out = ReferralTransitions.apply(submitted, ReferralStatus.PRIOR_AUTH_SUBMITTED,
                ReferralStatus.PRIOR_AUTH_APPROVED, NOW);
        assertThat(out.paDecidedAt()).isEqualTo(NOW);
    }

    @Test
    void activeTherapyAndCancelStampTimestamps() {
        State ready = new State("DELIVERY_SCHEDULED", UUID.randomUUID(), T0, T0, T0, T0, T0, null, null,
                false, BigDecimal.ZERO, BigDecimal.ZERO);
        State active = ReferralTransitions.apply(ready, ReferralStatus.DELIVERY_SCHEDULED,
                ReferralStatus.ACTIVE_THERAPY, NOW);
        assertThat(active.activeTherapyAt()).isEqualTo(NOW);
        assertThat(active.currentStatus()).isEqualTo("ACTIVE_THERAPY");

        State cancelled = ReferralTransitions.apply(ready, ReferralStatus.DELIVERY_SCHEDULED,
                ReferralStatus.CANCELLED, NOW);
        assertThat(cancelled.closedAt()).isEqualTo(NOW);
    }
}
