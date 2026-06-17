package com.midas.d3.context;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single pipeline event for full audit trail.
 */
@Getter
@Builder
public final class AuditEntry {

    public enum Severity { INFO, WARN, ERROR }

    private final String stage;
    private final String event;
    private final Severity severity;
    private final Instant timestamp;
    private final String detail;

    public static AuditEntry info(String stage, String event) {
        return of(stage, event, Severity.INFO, null);
    }

    public static AuditEntry warn(String stage, String event, String detail) {
        return of(stage, event, Severity.WARN, detail);
    }

    public static AuditEntry error(String stage, String event, String detail) {
        return of(stage, event, Severity.ERROR, detail);
    }

    private static AuditEntry of(String stage, String event, Severity severity, String detail) {
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        return AuditEntry.builder()
                .stage(stage)
                .event(event)
                .severity(severity)
                .timestamp(Instant.now())
                .detail(detail)
                .build();
    }
}
