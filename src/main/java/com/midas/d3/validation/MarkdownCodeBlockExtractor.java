package com.midas.d3.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts raw source from LLM responses wrapped in markdown code fences.
 */
public final class MarkdownCodeBlockExtractor {

    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "```([a-zA-Z0-9_-]+)?\\s*\\r?\\n(.*?)\\r?\\n?```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private MarkdownCodeBlockExtractor() {}

    public static String extract(String text) {
        Matcher matcher = MARKDOWN_FENCE.matcher(text);
        String fallback = null;
        while (matcher.find()) {
            String extracted = matcher.group(2);
            if (extracted == null) {
                continue;
            }
            String stripped = extracted.strip();
            if (stripped.isBlank()) {
                continue;
            }
            if (!stripped.startsWith("{")) {
                return stripped;
            }
            if (fallback == null) {
                fallback = stripped;
            }
        }
        return fallback;
    }
}
