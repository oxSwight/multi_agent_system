package com.midas.d3.agent.implementation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared support for <b>self-healing the assembled-envelope gate</b> of a per-file generation pass.
 *
 * <p>Per-file generation validates each file in isolation, then a final assembled-envelope check
 * enforces the cross-file contracts (script wiring, manifest references, a test that actually imports
 * its source, …). When that final check fails the pass historically dead-ended to a critical failure,
 * even though the defect is usually confined to one or two files. This support turns that dead-end
 * into a bounded heal: pick the file(s) the violations name, regenerate only those with the assembled
 * violations as feedback, and re-validate. Mechanical defects are already repaired deterministically
 * upstream ({@link ExtensionWiringNormalizer}); healing exists for the <em>semantic</em> classes a
 * deterministic pass cannot safely fix (a model that wrote a test against a phantom module, fabricated
 * element ids, an unhandled message action).
 */
final class AssembledHealingSupport {

    /** Bounded heal budget: each round is targeted regeneration of the named file(s), so 2 is ample. */
    static final int MAX_HEAL_ROUNDS = 2;

    /** Matches a bracketed token in a violation message, e.g. {@code [frontend/src/form.js]}. */
    private static final Pattern BRACKETED = Pattern.compile("\\[([^\\]\\[]+)]");

    private AssembledHealingSupport() {
    }

    /**
     * The generated file paths a set of assembled violations implicate — every bracketed token that is
     * an actual generated path. Quoted ids/snippets (e.g. {@code ['phone']}) and bare file names never
     * match a real path key, so they are naturally excluded. Order-preserving and de-duplicated.
     */
    static List<String> offendingPaths(List<String> violations, Set<String> generatedPaths) {
        Set<String> normalizedKeys = new LinkedHashSet<>();
        for (String key : generatedPaths) {
            normalizedKeys.add(normalize(key));
        }
        Set<String> offending = new LinkedHashSet<>();
        for (String violation : violations) {
            if (violation == null) {
                continue;
            }
            Matcher m = BRACKETED.matcher(violation);
            while (m.find()) {
                String candidate = normalize(m.group(1).strip());
                if (normalizedKeys.contains(candidate)) {
                    offending.add(candidate);
                }
            }
        }
        return List.copyOf(offending);
    }

    /** A correction block instructing the model to regenerate the target file so the cross-file checks pass. */
    static String healingFeedback(List<String> violations) {
        StringBuilder sb = new StringBuilder(
                "--- CROSS-FILE ASSEMBLY CHECK FAILED — your file is part of a project that did not "
                        + "assemble correctly ---\nFix ALL of the following in the regenerated TARGET FILE "
                        + "(keep every other behavior intact):\n");
        for (String violation : violations) {
            sb.append("  - ").append(violation).append('\n');
        }
        sb.append("Regenerate the TARGET FILE so every issue above is resolved.");
        return sb.toString();
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
