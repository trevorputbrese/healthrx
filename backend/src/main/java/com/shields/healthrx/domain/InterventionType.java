package com.shields.healthrx.domain;

public enum InterventionType implements LabeledEnum {
    ADHERENCE_COUNSELING("Adherence counseling"),
    SIDE_EFFECT_MANAGEMENT("Side effect management"),
    DOSE_CLARIFICATION("Dose clarification"),
    LAB_MONITORING("Lab monitoring"),
    CARE_COORDINATION("Care coordination");

    private final String label;

    InterventionType(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    /** Intervention types that resolve the refill-risk "unsuccessful outreach" condition. */
    public boolean resolvesOutreachRisk() {
        return this == ADHERENCE_COUNSELING || this == CARE_COORDINATION;
    }
}
