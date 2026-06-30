package com.midas.d3.agent.implementation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Cap on cross-file contract symbols (ids / message actions) surfaced per file. */
    private static final int MAX_CONTRACT_SYMBOLS = 25;

    private static final Pattern HTML_ID = Pattern.compile("\\bid=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEND_MESSAGE = Pattern.compile("sendMessage\\s*\\(");
    private static final Pattern ACTION_IN_PAYLOAD = Pattern.compile("(?:action|type)\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern HANDLED_ACTION = Pattern.compile(
            "(?:case\\s*|===?\\s*)['\"]([^'\"]+)['\"]");

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

    /**
     * Path-aware summary: the {@link #summarize(String) signature table} plus the cross-file
     * <b>contracts</b> a sibling needs to wire against — element ids exposed by an HTML file, and the
     * runtime message actions a JS file sends / handles. Injecting these into the next file's prompt
     * lets the model use the exact id/action up front, preventing the popup.js↔popup.html and
     * content_script↔background mismatches (and the remediation round-trips they would otherwise cost).
     */
    static String summarize(String source, String path) {
        String base = summarize(source);
        String contracts = contractHints(source, path);
        return contracts.isEmpty() ? base : base + "\n" + contracts;
    }

    private static String contractHints(String source, String path) {
        if (source == null || source.isBlank() || path == null) {
            return "";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        List<String> hints = new ArrayList<>();

        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            Set<String> ids = matchGroup1(HTML_ID, source);
            if (!ids.isEmpty()) {
                hints.add("// element ids: " + joinPrefixed(ids, "#"));
            }
        } else if (lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".jsx") || lower.endsWith(".tsx")) {
            Set<String> sent = sentActions(source);
            if (!sent.isEmpty()) {
                hints.add("// sends messages: " + joinQuoted(sent));
            }
            if (source.contains("onMessage")) {
                Set<String> handled = matchGroup1(HANDLED_ACTION, source);
                if (!handled.isEmpty()) {
                    hints.add("// handles messages: " + joinQuoted(handled));
                }
            }
        }
        return String.join("\n", hints);
    }

    /** Action/type strings inside message payloads, scoped to the window after each sendMessage(. */
    private static Set<String> sentActions(String source) {
        Set<String> actions = new LinkedHashSet<>();
        Matcher send = SEND_MESSAGE.matcher(source);
        while (send.find() && actions.size() < MAX_CONTRACT_SYMBOLS) {
            int start = send.end();
            int end = Math.min(source.length(), start + 200);
            Matcher action = ACTION_IN_PAYLOAD.matcher(source.substring(start, end));
            if (action.find()) {
                actions.add(action.group(1));
            }
        }
        return actions;
    }

    private static Set<String> matchGroup1(Pattern pattern, String source) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find() && values.size() < MAX_CONTRACT_SYMBOLS) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String joinPrefixed(Set<String> values, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(prefix).append(v);
        }
        return sb.toString();
    }

    private static String joinQuoted(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('\'').append(v).append('\'');
        }
        return sb.toString();
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
