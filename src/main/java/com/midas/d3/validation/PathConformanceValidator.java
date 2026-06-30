package com.midas.d3.validation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic path-conformance check: generated source paths must sit where the architect's
 * {@code file_layout} declared them — not nested under an extra wrapper directory.
 *
 * <h2>Why this exists</h2>
 * The Chrome-extension that passed the gate but would not load had its {@code manifest.json} buried
 * under an extra wrapper directory instead of at the load-root the layout declared. A browser loads
 * an extension from the directory that holds {@code manifest.json}; a stray wrapper segment breaks
 * every relative reference inside it. This catches that structural drift deterministically.
 *
 * <h2>Directional, not exhaustive</h2>
 * It flags <em>misplacement</em> (a generated path that equals a declared layout path prefixed with
 * one or more extra leading directory segments) rather than enforcing that every layout entry is
 * present. Code generation legitimately emits a subset/superset of {@code file_layout} (test paths
 * are produced by a later stage; manifest/package files are added beyond the layout), so a
 * completeness rule would false-fail; a wrapper rule pinpoints exactly the defect that breaks the
 * artifact.
 */
final class PathConformanceValidator {

    private PathConformanceValidator() {}

    static void validate(JsonNode sourceFiles, JsonNode architecture, List<String> violations) {
        if (sourceFiles == null || !sourceFiles.isObject() || architecture == null || !architecture.isObject()) {
            return;
        }
        JsonNode fileLayout = architecture.get("file_layout");
        if (fileLayout == null || !fileLayout.isArray() || fileLayout.isEmpty()) {
            return;
        }

        Set<String> layoutPaths = new LinkedHashSet<>();
        for (JsonNode entry : fileLayout) {
            if (entry.isTextual() && !entry.asText().isBlank()) {
                layoutPaths.add(WebResourcePaths.normalize(entry.asText().strip()));
            }
        }
        if (layoutPaths.isEmpty()) {
            return;
        }

        List<String> sourcePaths = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = sourceFiles.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().isTextual()) {
                sourcePaths.add(WebResourcePaths.normalize(entry.getKey()));
            }
        }

        for (String source : sourcePaths) {
            if (layoutPaths.contains(source)) {
                continue;
            }
            String expected = wrapperMatch(source, layoutPaths);
            if (expected != null) {
                violations.add("[" + source + "] is nested under an extra wrapper directory — the "
                        + "file_layout declares it at [" + expected + "]. Emit files at their exact "
                        + "declared file_layout path; do not add a src/ or duplicate wrapper directory "
                        + "(a browser extension loads from the directory holding manifest.json, so a "
                        + "stray wrapper breaks every relative reference).");
            }
        }
    }

    /**
     * Returns the declared layout path that {@code source} is a wrapper-prefixed variant of
     * ({@code source == <extraDir>/<layoutPath>}), or {@code null} when {@code source} is not a
     * misplacement of any declared path.
     */
    private static String wrapperMatch(String source, Set<String> layoutPaths) {
        for (String layout : layoutPaths) {
            if (!source.equals(layout) && source.endsWith("/" + layout)) {
                return layout;
            }
        }
        return null;
    }
}
