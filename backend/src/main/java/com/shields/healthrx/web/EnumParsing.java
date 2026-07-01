package com.shields.healthrx.web;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** Parses request enum values, throwing a 400 with the allowed values when invalid. */
public final class EnumParsing {

    private EnumParsing() {
    }

    public static <E extends Enum<E>> E require(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw ApiException.badRequest("INVALID_ENUM",
                    "Invalid value for " + field + ": " + raw,
                    Map.of("field", field, "allowed", allowed(type)));
        }
    }

    /** Validates an optional filter value; null/blank passes through as null. */
    public static <E extends Enum<E>> String optional(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return require(type, raw, field).name();
    }

    private static <E extends Enum<E>> String allowed(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
