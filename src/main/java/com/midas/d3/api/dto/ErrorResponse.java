package com.midas.d3.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * RFC-7807-inspired error envelope returned by {@link com.midas.d3.api.GlobalExceptionHandler}.
 *
 * <p>{@code violations} is {@code null} for single-message errors and populated only
 * when there are multiple constraint violations (e.g., bean validation, GoalKeeper).
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> violations
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse withViolations(int status, String error, String message,
                                               String path, List<String> violations) {
        return new ErrorResponse(Instant.now(), status, error, message, path, violations);
    }
}
