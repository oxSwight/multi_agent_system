package com.midas.d3.agent.implementation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compresses an already-generated source/test file into a compact <b>symbol table</b> — the
 * public / exported declarations (signatures only, bodies omitted) that a sibling file may need
 * in order to import or call it.
 *
 * <h2>Why (FinOps)</h2>
 * The per-file generation loop used to re-send the <em>full body</em> of every previously-generated
 * file into each subsequent file's prompt ("for import/reference consistency"). Across an N-file
 * pass that makes the prompt grow O(N²) in input tokens, and the growing block lands in the volatile
 * tail where prompt-prefix caching cannot absorb it. Consumers only need a sibling's <em>interface</em>
 * — class / function signatures and exports — so each prior file is collapsed to its declaration lines.
 *
 * <h2>How</h2>
 * Deliberately language-agnostic and dependency-free: a lightweight, allocation-cheap line heuristic
 * covers the polyglot output of the pipeline (Java / TypeScript / JavaScript / Python). It is
 * intentionally permissive (when unsure it keeps the line) and bounded by
 * {@link #MAX_SIGNATURES_PER_FILE} so a pathological file cannot re-inflate the prompt.
 */
final class SymbolTableExtractor {

    /** Hard cap on signature lines emitted per file, so the summary stays compact. */
    static final int MAX_SIGNATURES_PER_FILE = 40;

    /** Declaration keywords that, at the start of a (trimmed) line, mark a public-ish symbol. */
    private static final String[] DECLARATION_KEYWORDS = {
            "export", "public", "protected", "class", "interface", "enum", "record",
            "abstract", "function", "async", "def", "type", "struct", "trait"
    };

    private SymbolTableExtractor() {}

    /**
     * Returns a newline-separated list of signature lines for {@code source}, or a short placeholder
     * when the file is empty or exposes no detectable public symbols.
     */
    static String summarize(String source) {
        if (source == null || source.isBlank()) {
            return "(empty file — body omitted)";
        }

        List<String> signatures = new ArrayList<>();
        for (String raw : source.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || isComment(line) || !isDeclaration(line)) {
                continue;
            }
            signatures.add(compactSignature(line));
            if (signatures.size() >= MAX_SIGNATURES_PER_FILE) {
                signatures.add("// … (signatures truncated)");
                break;
            }
        }

        return signatures.isEmpty()
                ? "(no public symbols detected — body omitted)"
                : String.join("\n", signatures);
    }

    /** True when the trimmed line begins with a public/exported declaration keyword. */
    private static boolean isDeclaration(String line) {
        for (String kw : DECLARATION_KEYWORDS) {
            if (line.equals(kw) || line.startsWith(kw + " ") || line.startsWith(kw + "(")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isComment(String line) {
        return line.startsWith("//") || line.startsWith("*")
                || line.startsWith("/*") || line.startsWith("#");
    }

    /** Keeps the declaration up to its signature; drops a trailing body opener ({@code &#123;} or {@code =&gt;}). */
    private static String compactSignature(String line) {
        int brace = line.indexOf('{');
        String sig = (brace >= 0 ? line.substring(0, brace) : line).strip();
        if (sig.toLowerCase(Locale.ROOT).endsWith("=>")) {
            sig = sig.substring(0, sig.length() - 2).strip();
        }
        return sig;
    }
}
