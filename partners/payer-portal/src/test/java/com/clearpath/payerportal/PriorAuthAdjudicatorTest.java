package com.clearpath.payerportal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.clearpath.payerportal.PriorAuthAdjudicator.Decision;

class PriorAuthAdjudicatorTest {

    private final PriorAuthAdjudicator adjudicator = new PriorAuthAdjudicator(false);

    @Test
    void decisionsAreDeterministicPerReferralNumber() {
        Decision a = new PriorAuthAdjudicator(false).adjudicate("RX-1042", "Atlas Commercial", "Oncora");
        Decision b = new PriorAuthAdjudicator(false).adjudicate("RX-1042", "Atlas Commercial", "Oncora");
        assertThat(a.decision()).isEqualTo(b.decision());
    }

    @Test
    void approvalsCarryAnAuthNumberAndDenialsAReason() {
        // Sweep enough referral numbers to hit both branches of the deterministic rule.
        boolean sawApproved = false;
        boolean sawDenied = false;
        for (int i = 0; i < 40 && !(sawApproved && sawDenied); i++) {
            Decision d = adjudicator.adjudicate("RX-" + (2000 + i), "Plan", "Drug");
            if ("APPROVED".equals(d.decision())) {
                sawApproved = true;
                assertThat(d.authorizationNumber()).isNotBlank();
                assertThat(d.denialReason()).isNull();
            } else {
                sawDenied = true;
                assertThat(d.denialReason()).isNotBlank();
                assertThat(d.authorizationNumber()).isNull();
            }
        }
        assertThat(sawApproved).isTrue();
        assertThat(sawDenied).isTrue();
    }

    @Test
    void resubmissionOfADeniedReferralIsApproved() {
        String denied = null;
        for (int i = 0; i < 200; i++) {
            String number = "RX-" + (7000 + i);
            if ("DENIED".equals(adjudicator.adjudicate(number, "Plan", "Drug").decision())) {
                denied = number;
                break;
            }
        }
        assertThat(denied).as("the deterministic rule denies ~20%% of first submissions").isNotNull();

        Decision appeal = adjudicator.adjudicate(denied, "Plan", "Drug");
        assertThat(appeal.decision()).isEqualTo("APPROVED");
        assertThat(appeal.attempt()).isEqualTo(2);
    }
}
