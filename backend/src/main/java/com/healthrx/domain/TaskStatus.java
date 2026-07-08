package com.healthrx.domain;

public enum TaskStatus implements LabeledEnum {
    OPEN("Open"),
    IN_PROGRESS("In progress"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String label;

    TaskStatus(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }
}
