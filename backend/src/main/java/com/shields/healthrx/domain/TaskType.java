package com.shields.healthrx.domain;

public enum TaskType implements LabeledEnum {
    MISSING_LAB("Missing lab"),
    PRIOR_AUTH_RENEWAL("Prior authorization renewal"),
    PATIENT_CONTACT("Patient contact"),
    FINANCIAL_ASSISTANCE("Financial assistance"),
    REFILL_FOLLOW_UP("Refill follow-up"),
    CLINICAL_REVIEW("Clinical review"),
    ACCESS_FOLLOW_UP("Access follow-up");

    private final String label;

    TaskType(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }
}
