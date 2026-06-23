package com.midas.d3.build;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Enriches file-attributed {@link BuildDiagnostic}s with the offending source snippet pulled
 * directly from the source map the build actually compiled.
 *
 * <p><b>Why this exists.</b> A build log gives a coordinate ({@code file:line}) plus a message, but
 * the self-healing remediation agent then has to re-derive <em>which code</em> is broken — either by
 * being handed the whole source map again or by guessing from a noisy stack trace. Both waste tokens
 * and degrade the signal. This extractor closes the gap deterministically: it slices a few lines of
 * real context around the reported line out of the generated source, so the remediation directive
 * carries the exact broken snippet instead of environment noise.
 *
 * <p><b>Not an AST pass — by design.</b> The code under diagnosis frequently does not parse (that is
 * usually <em>why</em> the build failed), so an AST-based slice would be the wrong tool: it would
 * fail on exactly the inputs we most need to compress. Source-map line slicing is robust to
 * unparseable code and is the correct compression primitive here.
 *
 * <p>Pure and best-effort: anything it cannot resolve (no file attribution, line out of range, file
 * absent from the map) is left untouched, so a miss only forgoes an enhancement — it never blocks
 * the loop.
 */
public final class BuildSnippetExtractor {

    /** Lines of context to include on either side of the offending line. */
    public static final int CONTEXT_RADIUS = 2;

    /** Hard cap on a single snippet so a minified / one-line file can't blow the token budget. */
    public static final int MAX_SNIPPET_CHARS = 600;

    private BuildSnippetExtractor() {
    }

    /**
     * Returns a copy of {@code report} whose diagnostics carry a {@code codeSnippet} wherever a
     * {@code file}+{@code line} could be resolved against {@code sourceMap}. A passing report, an
     * empty diagnostic list, or a non-object source map is returned unchanged, as is the case where
     * no diagnostic could be resolved (no needless object churn).
     *
     * @param report    the build outcome to enrich; may be null (returned as-is)
     * @param sourceMap path→contents object the build compiled (merged source + tests)
     */
    public static BuildReport enrich(BuildReport report, JsonNode sourceMap) {
        if (report == null || report.success() || report.diagnostics().isEmpty()
                || sourceMap == null || !sourceMap.isObject()) {
            return report;
        }

        List<BuildDiagnostic> enriched = new ArrayList<>(report.diagnostics().size());
        boolean changed = false;
        for (BuildDiagnostic d : report.diagnostics()) {
            String snippet = snippetFor(d, sourceMap);
            if (!snippet.isEmpty()) {
                enriched.add(d.withSnippet(snippet));
                changed = true;
            } else {
                enriched.add(d);
            }
        }
        if (!changed) {
            return report;
        }
        return BuildReport.failure(report.tool(), report.exitCode(), enriched,
                report.summary(), report.rawOutputTail());
    }

    private static String snippetFor(BuildDiagnostic d, JsonNode sourceMap) {
        if (d.line() <= 0 || d.file() == null || d.file().isBlank() || "unknown".equals(d.file())) {
            return "";
        }
        String content = resolveContent(d.file(), sourceMap);
        if (content == null) {
            return "";
        }
        return sliceAround(content, d.line());
    }

    /**
     * Resolves the diagnostic file path against the source map. The build tool may report an
     * absolute sandbox path (e.g. {@code /tmp/midas-run/src/main/java/App.java}) while the map is
     * keyed by project-relative paths ({@code src/main/java/App.java}), so a key matches when it is a
     * path-suffix of the reported file (or vice-versa). The longest such match wins to avoid a short
     * key ({@code App.java}) shadowing a more specific one.
     */
    private static String resolveContent(String file, JsonNode sourceMap) {
        JsonNode exact = sourceMap.get(file);
        if (exact != null && exact.isTextual()) {
            return exact.asText();
        }

        String needle = normalize(file);
        String bestContent = null;
        int bestLen = -1;
        for (Iterator<String> it = sourceMap.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            String nk = normalize(key);
            if ((needle.endsWith(nk) || nk.endsWith(needle)) && nk.length() > bestLen) {
                JsonNode v = sourceMap.get(key);
                if (v != null && v.isTextual()) {
                    bestContent = v.asText();
                    bestLen = nk.length();
                }
            }
        }
        return bestContent;
    }

    private static String sliceAround(String content, int line) {
        String[] lines = content.split("\n", -1);
        if (line > lines.length) {
            return "";
        }
        int from = Math.max(1, line - CONTEXT_RADIUS);
        int to = Math.min(lines.length, line + CONTEXT_RADIUS);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(i == line ? "> " : "  ")
              .append(i).append(" | ").append(stripCr(lines[i - 1])).append('\n');
            if (sb.length() >= MAX_SNIPPET_CHARS) {
                break;
            }
        }
        String out = sb.toString();
        if (out.length() > MAX_SNIPPET_CHARS) {
            out = out.substring(0, MAX_SNIPPET_CHARS);
        }
        return out.stripTrailing();
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }
}
