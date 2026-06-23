package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Objects;

/**
 * The structured outcome of one sandboxed build of the generated project.
 *
 * <p>Serialized to the canonical JSON artifact consumed by the
 * {@code BUILD_VERIFICATION} pipeline stage:
 * <pre>
 * {
 *   "build_status": "SUCCESS" | "FAILED",
 *   "tool":         "MAVEN" | "GRADLE" | "NPM" | "NONE",
 *   "exit_code":    0,
 *   "summary":      "…",
 *   "diagnostics":  [ { "file": "…", "line": 12, "severity": "ERROR", "message": "…",
 *                       "code_snippet": "  11 | …\n> 12 | …\n  13 | …" } ],
 *   "raw_output_tail": "…"
 * }
 * </pre>
 *
 * <p>The {@code build_status} field is the load-bearing signal: a {@code FAILED} status
 * routes the pipeline back into {@code CODE_GENERATION} with the diagnostics attached as a
 * remediation directive; {@code SUCCESS} advances to the SecOps audit.
 */
public record BuildReport(
        boolean success,
        BuildTool tool,
        int exitCode,
        List<BuildDiagnostic> diagnostics,
        String summary,
        String rawOutputTail) {

    /** Hard cap on retained raw output so an over-chatty build never bloats the context. */
    public static final int MAX_RAW_TAIL_CHARS = 8_000;

    public BuildReport {
        tool = Objects.requireNonNullElse(tool, BuildTool.NONE);
        diagnostics = (diagnostics == null) ? List.of() : List.copyOf(diagnostics);
        summary = (summary == null) ? "" : summary;
        rawOutputTail = truncateTail(rawOutputTail);
    }

    // ── Factories ────────────────────────────────────────────────────────────

    /** A passing build (or a legitimately skipped one for non-buildable projects). */
    public static BuildReport success(BuildTool tool, String summary) {
        return new BuildReport(true, tool, 0, List.of(), summary, "");
    }

    /** Verification skipped: no recognized build descriptor — treated as a pass, not a failure. */
    public static BuildReport skipped(String reason) {
        return new BuildReport(true, BuildTool.NONE, 0, List.of(), reason, "");
    }

    public static BuildReport failure(BuildTool tool, int exitCode,
                                      List<BuildDiagnostic> diagnostics,
                                      String summary, String rawOutputTail) {
        return new BuildReport(false, tool, exitCode, diagnostics, summary, rawOutputTail);
    }

    // ── Derived views ────────────────────────────────────────────────────────

    public long errorCount() {
        return diagnostics.stream().filter(BuildDiagnostic::isError).count();
    }

    public String buildStatus() {
        return success ? "SUCCESS" : "FAILED";
    }

    /** Serializes this report to its canonical JSON artifact shape. */
    public JsonNode toJson(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        ObjectNode root = mapper.createObjectNode();
        root.put("build_status", buildStatus());
        root.put("tool", tool.name());
        root.put("exit_code", exitCode);
        root.put("summary", summary);
        ArrayNode diag = root.putArray("diagnostics");
        for (BuildDiagnostic d : diagnostics) {
            ObjectNode n = diag.addObject();
            n.put("file", d.file());
            n.put("line", d.line());
            n.put("severity", d.severity().name());
            n.put("message", d.message());
            // Emitted only when an offending snippet was resolved, so coordinate-only diagnostics
            // serialize exactly as before — and the remediation agent gets the broken code inline
            // instead of having to re-derive it from the raw output tail.
            if (d.hasSnippet()) {
                n.put("code_snippet", d.codeSnippet());
            }
        }
        root.put("raw_output_tail", rawOutputTail);
        return root;
    }

    private static String truncateTail(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= MAX_RAW_TAIL_CHARS) {
            return raw;
        }
        return raw.substring(raw.length() - MAX_RAW_TAIL_CHARS);
    }
}
