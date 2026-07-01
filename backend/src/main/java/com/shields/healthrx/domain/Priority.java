package com.shields.healthrx.domain;

public enum Priority implements LabeledEnum {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    URGENT("Urgent");

    private final String label;

    Priority(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }
}
