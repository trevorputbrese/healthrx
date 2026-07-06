package com.bridgefund.assistanceportal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.bridgefund.assistanceportal.FinancialAssistanceAdjudicator.Decision;

class FinancialAssistanceAdjudicatorTest {

    private final FinancialAssistanceAdjudicator adjudicator = new FinancialAssistanceAdjudicator(false);

    @Test
    void decisionsAreDeterministicAndDoNotChangeOnRepeatCalls() {
        // Unlike ClearPath, there is no resubmission/appeal path: the same referral number
        // always gets the same answer, however many times it's asked.
        Decision a = new FinancialAssistanceAdjudicator(false).decide("RX-9042", "Oncora", 900);
        Decision b = new FinancialAssistanceAdjudicator(false).decide("RX-9042", "Oncora", 900);
        assertThat(a.decision()).isEqualTo(b.decision());
        assertThat(a.securedAmount()).isEqualTo(b.securedAmount());

        Decision again = adjudicator.decide("RX-9042", "Oncora", 900);
        Decision repeat = adjudicator.decide("RX-9042", "Oncora", 900);
        assertThat(again.decision()).isEqualTo(repeat.decision());
        assertThat(again.securedAmount()).isEqualTo(repeat.securedAmount());
    }

    @Test
    void approvalsCarryASecuredAmountAndDenialsAReason() {
        boolean sawApproved = false;
        boolean sawDenied = false;
        for (int i = 0; i < 60 && !(sawApproved && sawDenied); i++) {
            Decision d = adjudicator.decide("RX-" + (3000 + i), "Drug", 800);
            if ("APPROVED".equals(d.decision())) {
                sawApproved = true;
                assertThat(d.securedAmount()).isNotNull().isBetween(250, 5000);
                assertThat(d.denialReason()).isNull();
            } else {
                sawDenied = true;
                assertThat(d.denialReason()).isNotBlank();
                assertThat(d.securedAmount()).isNull();
            }
        }
        assertThat(sawApproved).isTrue();
        assertThat(sawDenied).isTrue();
    }

    @Test
    void firstTwoDistinctReferralsAlwaysApprove() {
        Decision first = adjudicator.decide("RX-5001", "Drug", 800);
        Decision second = adjudicator.decide("RX-5002", "Drug", 800);
        assertThat(first.decision()).isEqualTo("APPROVED");
        assertThat(second.decision()).isEqualTo("APPROVED");
    }

    @Test
    void securedAmountRespectsCopayBounds() {
        Decision small = adjudicator.decide("RX-6001", "Drug", 100);
        if ("APPROVED".equals(small.decision())) {
            assertThat(small.securedAmount()).isGreaterThanOrEqualTo(250); // floored
        }
        Decision large = adjudicator.decide("RX-6002", "Drug", 20000);
        if ("APPROVED".equals(large.decision())) {
            assertThat(large.securedAmount()).isLessThanOrEqualTo(5000); // capped
        }
    }
}
