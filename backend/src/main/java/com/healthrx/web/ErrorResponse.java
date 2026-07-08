package com.healthrx.web;

import java.util.Map;

/** Standard API error body. See api-contracts.md (Error Model). */
public record ErrorResponse(String code, String message, Map<String, Object> details) {
}
