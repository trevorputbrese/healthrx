package com.shields.healthrx.domain;

public enum OutreachChannel implements LabeledEnum {
    PHONE("Phone"),
    SMS("SMS"),
    EMAIL("Email"),
    PORTAL("Portal");

    private final String label;

    OutreachChannel(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }
}
