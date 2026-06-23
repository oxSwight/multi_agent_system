package com.midas.d3.build;

/**
 * Thrown for genuinely exceptional build conditions — a missing toolchain, an unreadable
 * sandbox, or a timed-out process — as distinct from an ordinary failed compile (which is
 * reported as a {@code FAILED} {@link BuildReport}, never thrown).
 */
public class BuildExecutionException extends RuntimeException {

    public BuildExecutionException(String message) {
        super(message);
    }

    public BuildExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
