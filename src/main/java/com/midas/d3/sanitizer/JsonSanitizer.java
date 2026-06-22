package com.midas.d3.sanitizer;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless utility that extracts a clean JSON object string from raw LLM output.
 *
 * <h2>Problem</h2>
 * LLMs frequently wrap JSON in Markdown code fences, add preamble text, append
 * explanations, or produce multi-block responses. None of these survive Jackson parsing.
 *
 * <h2>Extraction strategy (ordered, stops at first success)</h2>
 * <ol>
 *   <li><b>Markdown fence</b> — extracts the content of {@code ```json … ```} or {@code ``` … ```}</li>
 *   <li><b>Bare JSON</b> — if the trimmed string already starts with {@code {}, return it as-is</li>
 *   <li><b>Brace extraction</b> — finds the first {@code {} and matches the closing {@code }}
 *       with a character-level state machine that correctly skips strings and escape sequences</li>
 *   <li><b>Fallback</b> — returns the trimmed input unchanged; the downstream validator
 *       will produce an appropriate error message</li>
 * </ol>
 *
 * <h2>What this does NOT do</h2>
 * This class does not validate JSON — that is the responsibility of
 * {@link com.midas.d3.validation.GoalKeeperValidator}. It only strips non-JSON noise.
 */
@Slf4j
public final class JsonSanitizer {

    private JsonSanitizer() {}

    /**
     * Captures content inside any backtick code fence (with optional "json"/"JSON" language tag).
     * Handles both LF-only and CRLF line endings.
     */
    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "```([a-zA-Z0-9_-]+)?\\s*\\r?\\n?(.*?)\\r?\\n?```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** DeepSeek-R1 and similar reasoning models wrap chain-of-thought in these tags. */
    private static final Pattern REASONING_BLOCK = Pattern.compile(
            "<" + "think" + ">.*?</" + "think" + ">",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Sanitizes raw LLM output and returns the extracted JSON string.
     *
     * @param rawLlmOutput raw string from the LLM; may be null, blank, or wrapped in markdown
     * @return extracted JSON string (may still be syntactically invalid — validated downstream)
     */
    public static String sanitize(String rawLlmOutput) {
        if (rawLlmOutput == null || rawLlmOutput.isBlank()) {
            log.debug("[JsonSanitizer] Input is null or blank — returning as-is.");
            return rawLlmOutput;
        }

        String trimmed = stripPreamble(stripReasoningBlocks(rawLlmOutput.strip()));

        String fromFence = extractBestJsonFromFences(trimmed);
        if (fromFence != null) {
            log.debug("[JsonSanitizer] Extracted from markdown fence ({} → {} chars).",
                    trimmed.length(), fromFence.length());
            return fromFence;
        }

        if (trimmed.startsWith("{")) {
            log.debug("[JsonSanitizer] Input starts with '{' — extracting JSON object.");
            return extractJsonObject(trimmed, 0);
        }

        // Strategy 3: Find the first '{' and extract the enclosing JSON object
        int braceStart = trimmed.indexOf('{');
        if (braceStart != -1) {
            String extracted = extractJsonObject(trimmed, braceStart);
            log.debug("[JsonSanitizer] Extracted JSON object via brace scan (start={}).", braceStart);
            return extracted;
        }

        // Fallback: return trimmed; downstream validator will explain the failure
        log.warn("[JsonSanitizer] No JSON object found in input (len={}). Returning trimmed.", trimmed.length());
        return trimmed;
    }

    static String stripReasoningBlocks(String text) {
        String stripped = REASONING_BLOCK.matcher(text).replaceAll("").strip();
        return stripped.isEmpty() ? text.strip() : stripped;
    }

    static String stripPreamble(String trimmed) {
        if (trimmed.startsWith("{") || trimmed.startsWith("```")) {
            return trimmed;
        }
        int fenceIdx = trimmed.indexOf("```");
        int braceIdx = trimmed.indexOf('{');
        int start = -1;
        if (fenceIdx >= 0 && braceIdx >= 0) {
            start = Math.min(fenceIdx, braceIdx);
        } else if (fenceIdx >= 0) {
            start = fenceIdx;
        } else if (braceIdx >= 0) {
            start = braceIdx;
        }
        if (start > 0) {
            return trimmed.substring(start).stripLeading();
        }
        return trimmed;
    }

    static String extractBestJsonFromFences(String text) {
        Matcher fenceMatcher = MARKDOWN_FENCE.matcher(text);
        String fallback = null;
        while (fenceMatcher.find()) {
            String extracted = fenceMatcher.group(2).strip();
            if (extracted.isBlank()) {
                continue;
            }
            if (extracted.startsWith("{")) {
                return extracted;
            }
            if (fallback == null) {
                fallback = extracted;
            }
        }
        return fallback;
    }

    /**
     * Extracts the outermost JSON object starting at {@code start} using a
     * character-level state machine. Correctly handles:
     * <ul>
     *   <li>Nested objects: {@code {"a": {"b": 1}}}</li>
     *   <li>Escaped quotes in strings: {@code {"k": "val \" here"}}</li>
     *   <li>Escape sequences: {@code {"k": "line\\nbreak"}}</li>
     *   <li>Braces inside string values: {@code {"k": "text {with} braces"}}</li>
     * </ul>
     *
     * @param text  the full input string
     * @param start index of the first {@code {}
     * @return substring from {@code start} to the matching {@code }}  (inclusive);
     *         if no closing brace is found, returns from {@code start} to end of string
     */
    static String extractJsonObject(String text, int start) {
        int depth    = 0;
        boolean inString = false;
        boolean escaped  = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{')      depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return text.substring(start, i + 1);
                }
            }
        }

        // Unclosed brace — return from start to end; validator will report the error
        return text.substring(start);
    }
}
