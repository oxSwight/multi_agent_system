package com.midas.d3.build;

import java.util.Objects;

/**
 * A single diagnostic (error/warning) parsed from a build tool's output.
 *
 * @param file     source file the diagnostic refers to ({@code "unknown"} if the tool
 *                 did not attribute it to a file)
 * @param line     1-based line number, or {@code 0} when not available
 * @param severity diagnostic severity; never null
 * @param message  human-readable diagnostic text; never blank
 */
public record BuildDiagnostic(String file, int line, BuildSeverity severity, String message) {

    public BuildDiagnostic {
        file = (file == null || file.isBlank()) ? "unknown" : file.strip();
        severity = Objects.requireNonNullElse(severity, BuildSeverity.ERROR);
        message = (message == null) ? "" : message.strip();
        if (line < 0) {
            line = 0;
        }
    }

    public static BuildDiagnostic error(String file, int line, String message) {
        return new BuildDiagnostic(file, line, BuildSeverity.ERROR, message);
    }

    public boolean isError() {
        return severity == BuildSeverity.ERROR;
    }
}
