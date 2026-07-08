package com.healthrx.domain;

/** An enum whose constants carry a human-friendly display label for lookups and the UI. */
public interface LabeledEnum {
    String name();

    String label();
}
