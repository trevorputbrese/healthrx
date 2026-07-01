package com.shields.healthrx.domain;

public enum TherapyStatus implements LabeledEnum {
    PENDING_ACCESS("Pending access"),
    ACTIVE("Active"),
    PAUSED("Paused"),
    DISCONTINUED("Discontinued");

    private final String label;

    TherapyStatus(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }
}
