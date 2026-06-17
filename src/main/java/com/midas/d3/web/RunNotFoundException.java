package com.midas.d3.web;

/**
 * Thrown by {@link DashboardService} when a requested pipeline run ID does not
 * exist in the {@code midas_run} table.
 *
 * <p>Mapped to {@code HTTP 404 Not Found} by {@link com.midas.d3.api.GlobalExceptionHandler}.
 */
public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(String message) {
        super(message);
    }
}
