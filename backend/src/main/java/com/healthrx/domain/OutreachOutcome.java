package com.healthrx.domain;

public enum OutreachOutcome implements LabeledEnum {
    REACHED("Reached"),
    LEFT_MESSAGE("Left message"),
    NO_ANSWER("No answer"),
    DECLINED("Declined"),
    NEEDS_FOLLOW_UP("Needs follow-up");

    private final String label;

    OutreachOutcome(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /** Outcomes that count toward the refill-risk "unsuccessful outreach" condition. */
    public boolean isUnsuccessful() {
        return this == LEFT_MESSAGE || this == NO_ANSWER || this == NEEDS_FOLLOW_UP;
    }
}
