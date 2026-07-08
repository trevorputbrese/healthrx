package com.healthrx.domain;

public enum FillStatus implements LabeledEnum {
    SCHEDULED("Scheduled"),
    DISPENSED("Dispensed"),
    DELAYED("Delayed"),
    CANCELLED("Cancelled"),
    MISSED("Missed");

    private final String label;

    FillStatus(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }
}
