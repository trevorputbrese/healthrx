package com.healthrx.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A domain/API error carrying the HTTP status, machine-readable code, and optional details
 * rendered into the {@link ErrorResponse} body by {@link GlobalExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    // --- Factories for the common cases in api-contracts.md ---

    public static ApiException notFound(String resource, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                resource + " not found: " + id, detail("id", id));
    }

    public static ApiException badRequest(String code, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, details);
    }

    public static ApiException unprocessable(String code, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message, details);
    }

    public static ApiException conflict(String code, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public static ApiException invalidTransition(String from, String to) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                "Cannot move referral from " + from + " to " + to + ".",
                detail("fromStatus", from, "toStatus", to));
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
