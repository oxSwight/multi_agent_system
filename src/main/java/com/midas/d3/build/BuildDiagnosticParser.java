package com.midas.d3.build;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of structured {@link BuildDiagnostic}s from raw build-tool output.
 *
 * <p>Parsing is deliberately tolerant: any line it cannot attribute is ignored. Even when it
 * extracts nothing, the caller still has the non-zero exit code and the raw output tail to feed
 * back — so a parser miss degrades the remediation signal, it never blocks the loop.
 */
public final class BuildDiagnosticParser {

    /** javac / Maven compiler: {@code /abs/Path.java:[12,34] message}  or  {@code Path.java:12: error: message}. */
    private static final Pattern MAVEN_JAVAC = Pattern.compile(
            "(?m)^(?:\\[ERROR]\\s*)?(\\S+\\.java):\\[?(\\d+)(?:,\\d+)?]?:?\\s*(?:error:?)?\\s*(.+)$");

    /** TypeScript / many JS bundlers: {@code src/app.ts(12,34): error TS2345: message}. */
    private static final Pattern TSC = Pattern.compile(
            "(?m)^(\\S+\\.[a-zA-Z]+)\\((\\d+),\\d+\\):\\s*error\\s*[A-Z0-9]*:?\\s*(.+)$");

    /** Generic fallback: a line that begins with an ERROR marker. */
    private static final Pattern GENERIC_ERROR = Pattern.compile(
            "(?m)^(?:\\[ERROR]|ERROR|error)\\s+(.{4,})$");

    /** Cap so a pathological build log can't produce thousands of diagnostics. */
    public static final int MAX_DIAGNOSTICS = 50;

    private BuildDiagnosticParser() {
    }

    public static List<BuildDiagnostic> parse(BuildTool tool, String output) {
        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return diagnostics;
        }

        Pattern primary = (tool == BuildTool.NPM) ? TSC : MAVEN_JAVAC;
        collect(primary, output, diagnostics);

        if (diagnostics.isEmpty()) {
            collectGeneric(output, diagnostics);
        }
        return diagnostics;
    }

    private static void collect(Pattern pattern, String output, List<BuildDiagnostic> out) {
        Matcher m = pattern.matcher(output);
        while (m.find() && out.size() < MAX_DIAGNOSTICS) {
            int line = safeInt(m.group(2));
            out.add(BuildDiagnostic.error(m.group(1), line, m.group(3)));
        }
    }

    private static void collectGeneric(String output, List<BuildDiagnostic> out) {
        Matcher m = GENERIC_ERROR.matcher(output);
        while (m.find() && out.size() < MAX_DIAGNOSTICS) {
            String msg = m.group(1).strip();
            if (!msg.isBlank()) {
                out.add(BuildDiagnostic.error("unknown", 0, msg));
            }
        }
    }

    private static int safeInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
