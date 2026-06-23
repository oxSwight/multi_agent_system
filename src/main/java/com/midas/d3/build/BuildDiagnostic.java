package com.midas.d3.build;

import java.util.Objects;

/**
 * A single diagnostic (error/warning) parsed from a build tool's output.
 *
 * @param file        source file the diagnostic refers to ({@code "unknown"} if the tool
 *                    did not attribute it to a file)
 * @param line        1-based line number, or {@code 0} when not available
 * @param severity    diagnostic severity; never null
 * @param message     human-readable diagnostic text; never blank
 * @param codeSnippet the offending source line plus a little context, sliced from the source map
 *                    that was compiled ({@code ""} until {@link BuildSnippetExtractor} enriches it,
 *                    or when the file+line could not be resolved); never null
 */
public record BuildDiagnostic(String file, int line, BuildSeverity severity, String message, String codeSnippet) {

    public BuildDiagnostic {
        file = (file == null || file.isBlank()) ? "unknown" : file.strip();
        severity = Objects.requireNonNullElse(severity, BuildSeverity.ERROR);
        message = (message == null) ? "" : message.strip();
        codeSnippet = (codeSnippet == null) ? "" : codeSnippet;
        if (line < 0) {
            line = 0;
        }
    }

    public static BuildDiagnostic error(String file, int line, String message) {
        return new BuildDiagnostic(file, line, BuildSeverity.ERROR, message, "");
    }

    /** Returns a copy of this diagnostic carrying the given offending-source snippet. */
    public BuildDiagnostic withSnippet(String snippet) {
        return new BuildDiagnostic(file, line, severity, message, snippet);
    }

    public boolean hasSnippet() {
        return codeSnippet != null && !codeSnippet.isBlank();
    }

    public boolean isError() {
        return severity == BuildSeverity.ERROR;
    }
}
