package com.midas.d3.build;

/**
 * Severity of a single {@link BuildDiagnostic} emitted by a build tool.
 *
 * <p>Only {@link #ERROR} diagnostics make a build {@code FAILED}; warnings and
 * info are surfaced for context but never fail the verification gate on their own.
 */
public enum BuildSeverity {
    ERROR,
    WARNING,
    INFO
}
